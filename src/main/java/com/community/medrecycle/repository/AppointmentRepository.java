package com.community.medrecycle.repository;

import com.community.medrecycle.domain.Appointment;
import com.community.medrecycle.domain.enums.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    Optional<Appointment> findByAppointmentNo(String appointmentNo);
    List<Appointment> findByResidentIdOrderByCreatedAtDesc(Long residentId);
    List<Appointment> findBySiteIdAndStatus(Long siteId, AppointmentStatus status);
}
