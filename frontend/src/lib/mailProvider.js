/**
 * Works out where someone should go to read the email we just sent them.
 *
 * WHY NOT ALWAYS LINK TO GMAIL: most addresses are not Gmail, and a button that
 * opens the wrong provider's login page is worse than no button -- it looks
 * like the app is confused about who you are. So the domain decides, and
 * anything unrecognised gets no button at all rather than a guess.
 *
 * These open the provider's INBOX, not a specific message. No web mail client
 * lets an outside link select a message, and a deep link that 404s would be the
 * one thing worse than an extra click.
 */
const PROVIDERS = [
  {
    match: ["gmail.com", "googlemail.com"],
    name: "Gmail",
    // The search narrows the inbox to our message, which is as close to
    // "open the email" as an external link can get.
    url: "https://mail.google.com/mail/u/0/#search/from%3A(pawhome)+in%3Aanywhere",
  },
  {
    match: ["outlook.com", "hotmail.com", "live.com", "msn.com"],
    name: "Outlook",
    url: "https://outlook.live.com/mail/0/",
  },
  { match: ["yahoo.com", "ymail.com"], name: "Yahoo Mail", url: "https://mail.yahoo.com/" },
  { match: ["proton.me", "protonmail.com"], name: "Proton Mail", url: "https://mail.proton.me/" },
  { match: ["icloud.com", "me.com", "mac.com"], name: "iCloud Mail", url: "https://www.icloud.com/mail/" },
  { match: ["zoho.com"], name: "Zoho Mail", url: "https://mail.zoho.com/" },
  { match: ["aol.com"], name: "AOL Mail", url: "https://mail.aol.com/" },
];

/**
 * @returns {{name: string, url: string} | null} null when the domain is not one
 *   we recognise -- a university or company address, most often, where the
 *   right destination is genuinely unknowable.
 */
export function mailProviderFor(email) {
  const at = (email ?? "").lastIndexOf("@");
  if (at < 0) return null;
  const domain = email.slice(at + 1).trim().toLowerCase();
  if (!domain) return null;

  const found = PROVIDERS.find((p) => p.match.includes(domain));
  return found ? { name: found.name, url: found.url } : null;
}
