package com.pawhome.backend.care;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CareTaskRepository extends JpaRepository<CareTask, Long> {
}
