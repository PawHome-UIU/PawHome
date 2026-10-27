import EligibilityQuizModal from "./EligibilityQuizModal";

/**
 * The boarding quiz.
 *
 * THIS USED TO BE A HAND-WRITTEN FIVE-QUESTION WIZARD, and its own header
 * comment predicted its replacement: "when the questions come from the API,
 * both modals become the same component with a different fetch." They have,
 * so it is. The bank in quiz_questions holds all three quizzes -- ELIGIBILITY,
 * PERSONALITY and BOARDING are values of one discriminator -- and an admin
 * editing the boarding questions on the Quiz Questions page now changes what
 * this shows, which a hard-coded array could never do.
 *
 * It stays as its own file rather than the call site importing the shared
 * modal directly, because "BoardingQuizModal" is what the boarding page means
 * and a bare quizType string passed to something called EligibilityQuizModal
 * reads like a mistake every time somebody finds it.
 */
export default function BoardingQuizModal(props) {
  return <EligibilityQuizModal {...props} quizType="BOARDING" />;
}
