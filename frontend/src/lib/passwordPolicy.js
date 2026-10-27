/**
 * Password rules, mirroring PasswordPolicy.java on the server.
 *
 * THIS IS A COURTESY, NOT A CONTROL. Everything here runs in the browser, where
 * the person typing controls all of it -- the server checks the identical rules
 * and is what actually decides. What this buys is telling someone their
 * password is too short while they are still typing, instead of after a round
 * trip and a form reset.
 *
 * KEEP THE TWO IN STEP. If a rule changes here it changes there, or someone
 * sails past this form and gets a 400 they cannot explain.
 */
export const MIN_LENGTH = 8;
export const MAX_LENGTH = 72;

/** Anything that is not a letter, digit or space. Same set as the server. */
const SPECIALS = "!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~";

/** Shown under the field, so the requirement is stated before it is failed. */
export const DESCRIPTION =
  "At least 8 characters, with one uppercase letter and one special character.";

/** The individual rules, in the order the checklist shows them. */
export const RULES = [
  { id: "length", label: `At least ${MIN_LENGTH} characters`,
    test: (p) => p.length >= MIN_LENGTH },
  { id: "upper", label: "One uppercase letter",
    test: (p) => /[A-Z]/.test(p) },
  { id: "special", label: "One special character",
    test: (p) => [...p].some((c) => SPECIALS.includes(c)) },
];

/**
 * The first unmet rule, as a sentence, or null when the password passes.
 *
 * One message at a time, matching the server. A list of four complaints at once
 * makes someone fix all of them and still guess which mattered.
 */
export function passwordError(password) {
  const p = password ?? "";
  if (!p) return "Password is required";
  if (p.length < MIN_LENGTH) return `Use at least ${MIN_LENGTH} characters`;
  // BCrypt truncates at 72 bytes: a longer passphrase would appear to work and
  // then match on its first 72 characters.
  if (p.length > MAX_LENGTH) return `Use at most ${MAX_LENGTH} characters`;
  if (!/[A-Z]/.test(p)) return "Include at least one uppercase letter";
  if (![...p].some((c) => SPECIALS.includes(c))) {
    return "Include at least one special character, such as ! ? or #";
  }
  return null;
}

export function isValidPassword(password) {
  return passwordError(password) === null;
}
