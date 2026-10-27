package com.pawhome.backend.health;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VaccinationRepository extends JpaRepository<Vaccination, Long> {
}
