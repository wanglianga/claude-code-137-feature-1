package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.TxnType;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 积分账户：发放/扣回/调账/兑换。兑换走悲观锁应对峰值并发。 */
@Service
@RequiredArgsConstructor
public class PointsService {

    private final PointsAccountRepository accountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final GiftRepository giftRepository;
    private final PointsExchangeRepository exchangeRepository;

    @Transactional
    public int grant(Long residentId, int points, String refType, String refId, String remark) {
        return apply(residentId, points, TxnType.GRANT, refType, refId, remark);
    }

    @Transactional
    public int revoke(Long residentId, int points, String refType, String refId, String remark) {
        return apply(residentId, -points, TxnType.REVOKE, refType, refId, remark);
    }

    @Transactional
    public int adjust(Long residentId, int signedDelta, String refType, String refId, String remark) {
        return apply(residentId, signedDelta, TxnType.ADJUST, refType, refId, remark);
    }

    /** 账户加减积分并记流水（悲观锁保证串行） */
    private int apply(Long residentId, int signedAmount, TxnType type, String refType, String refId, String remark) {
        PointsAccount account = lockOrCreate(residentId);
        account.setBalance(account.getBalance() + signedAmount);
        accountRepository.save(account);

        PointsTransaction txn = new PointsTransaction();
        txn.setAccountId(account.getId());
        txn.setType(type);
        txn.setAmount(signedAmount);
        txn.setBalanceAfter(account.getBalance());
        txn.setRefType(refType);
        txn.setRefId(refId);
        txn.setRemark(remark);
        transactionRepository.save(txn);
        return account.getBalance();
    }

    private PointsAccount lockOrCreate(Long residentId) {
        return accountRepository.lockByResidentId(residentId).orElseGet(() -> {
            PointsAccount account = new PointsAccount();
            account.setResidentId(residentId);
            account.setBalance(0);
            return accountRepository.save(account);
        });
    }

    @Transactional(readOnly = true)
    public int balanceOf(Long residentId) {
        return accountRepository.findByResidentId(residentId).map(PointsAccount::getBalance).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<PointsTransaction> transactionsOf(Long residentId) {
        return accountRepository.findByResidentId(residentId)
                .map(a -> transactionRepository.findByAccountIdOrderByCreatedAtDesc(a.getId()))
                .orElse(List.of());
    }

    /**
     * 积分兑换。业务失败（余额不足/库存不足）不抛异常，而是落一条 FAILED 兑换单，
     * 便于追踪"积分兑换失败"场景；峰值并发由账户+礼品双悲观锁保证不超卖。
     */
    @Transactional
    public PointsExchange exchange(Long residentId, Long giftId) {
        // 直接悲观锁读取（先账户后礼品，固定顺序避免死锁）；
        // 不能先普通 findById 再锁，否则锁升级时的版本校验会在并发下误报冲突
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.badRequest("居民积分账户不存在"));
        Gift lockedGift = giftRepository.lockById(giftId)
                .orElseThrow(() -> BizException.notFound("礼品不存在: " + giftId));

        PointsExchange exchange = new PointsExchange();
        exchange.setExchangeNo(NoGen.next("EXC"));
        exchange.setResidentId(residentId);
        exchange.setGiftId(lockedGift.getId());
        exchange.setGiftName(lockedGift.getName());
        exchange.setCostPoints(lockedGift.getCostPoints());

        if (lockedGift.getStock() <= 0) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason("礼品库存不足");
            return exchangeRepository.save(exchange);
        }
        if (account.getBalance() < lockedGift.getCostPoints()) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason("积分余额不足，当前余额 " + account.getBalance());
            return exchangeRepository.save(exchange);
        }

        lockedGift.setStock(lockedGift.getStock() - 1);
        giftRepository.save(lockedGift);

        account.setBalance(account.getBalance() - lockedGift.getCostPoints());
        accountRepository.save(account);

        PointsTransaction txn = new PointsTransaction();
        txn.setAccountId(account.getId());
        txn.setType(TxnType.EXCHANGE);
        txn.setAmount(-lockedGift.getCostPoints());
        txn.setBalanceAfter(account.getBalance());
        txn.setRefType("EXCHANGE");
        txn.setRefId(exchange.getExchangeNo());
        txn.setRemark("兑换礼品: " + lockedGift.getName());
        transactionRepository.save(txn);

        exchange.setStatus(ExchangeStatus.SUCCESS);
        return exchangeRepository.save(exchange);
    }

    @Transactional(readOnly = true)
    public List<PointsExchange> exchangesOf(Long residentId) {
        return exchangeRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }
}
