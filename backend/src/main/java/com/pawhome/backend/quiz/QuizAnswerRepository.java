package com.pawhome.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, Long> {
}
