package com.pawhome.backend.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends the application's email.
 *
 * SENDING IS ASYNCHRONOUS AND NEVER FAILS A REQUEST. An SMTP round trip takes
 * anywhere from 200ms to a timeout, and Gmail is not part of this application's
 * uptime. If registration were to fail because a mail server was slow, the
 * account would already be written and the person would be told it was not.
 * So: the account is created, the response goes back, and the mail is attempted
 * on another thread. A failure is logged loudly and the person can ask for a
 * new link.
 *
 * THE ADDRESS AND CREDENTIALS COME FROM CONFIGURATION, never from code. The
 * app password in particular must reach this process through an environment
 * variable -- see application.yaml.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Value("${app.mail.from-name:PawHome}")
    private String fromName;

    @Value("${app.mail.enabled:true}")
    private boolean enabled;

    @Value("${app.public-url:http://localhost:5174}")
    private String publicUrl;

    /**
     * Send one HTML message.
     *
     * @Async, so the caller returns immediately. Requires @EnableAsync -- see
     * AsyncConfig. Without that annotation this runs inline and silently
     * reintroduces exactly the latency it exists to avoid.
     */
    @Async
    public void send(String to, String subject, String html) {
        if (!enabled) {
            // The escape hatch for tests and for demoing on a train. The body
            // is NOT logged: these messages carry verification and reset links,
            // and a log file is a much easier thing to read than an inbox.
            log.info("[mail disabled] would have sent \"{}\" to {}", subject, to);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Sent \"{}\" to {}", subject, to);
        } catch (MailException | jakarta.mail.MessagingException
                 | java.io.UnsupportedEncodingException e) {
            // Never rethrown. The caller has already committed its work and
            // responded; there is nothing useful for it to do about this.
            log.error("Could not send \"{}\" to {}: {}", subject, to, e.getMessage());
        }
    }

    public void sendVerification(String to, String name, String rawToken) {
        String link = publicUrl + "/verify-email?token=" + rawToken;
        send(to, "Confirm your PawHome email", layout(
                "Confirm your email",
                "<p>Hi " + escape(name) + ",</p>"
                + "<p>Please confirm this address so you can sign in to PawHome.</p>",
                "Confirm my email", link,
                "This link works once and expires in 48 hours. "
                + "If you didn't create a PawHome account, you can ignore this email "
                + "&mdash; nothing will happen until the link is used."));
    }

    public void sendPasswordReset(String to, String name, String rawToken) {
        String link = publicUrl + "/reset-password?token=" + rawToken;
        send(to, "Reset your PawHome password", layout(
                "Reset your password",
                "<p>Hi " + escape(name) + ",</p>"
                + "<p>Someone asked to reset the password for this account.</p>",
                "Choose a new password", link,
                "This link works once and expires in one hour. If it wasn't you, "
                + "ignore this email &mdash; your password has not changed."));
    }

    /**
     * "An administrator has added you to a shelter -- choose a password."
     *
     * NAMES THE SHELTER AND THE PERSON WHO SENT IT. An unexpected email asking
     * you to set a password is indistinguishable from a phishing attempt
     * unless it can tell you something only a real invitation would know.
     */
    public void sendStaffInvite(String to, String name, String shelterName,
                                String invitedBy, String rawToken) {
        String link = publicUrl + "/accept-invite?token=" + rawToken;
        send(to, "You've been added to " + shelterName + " on PawHome", layout(
                "Set up your PawHome account",
                "<p>Hi " + escape(name) + ",</p>"
                + "<p>" + escape(invitedBy) + " has added you as staff at <strong>"
                + escape(shelterName) + "</strong> on PawHome.</p>"
                + "<p>Your account is ready. Choose a password to sign in.</p>",
                "Choose your password", link,
                "This link works once and expires in seven days. If you were not "
                + "expecting this, ignore this email &mdash; the account cannot "
                + "be used until somebody sets a password on it."));
    }

    /**
     * A vaccination reminder for the person who adopted the pet.
     *
     * The shelter is named because an owner with a question needs to know who
     * to ask, and the pet is named because an owner with two pets needs to know
     * which one this is about.
     */
    public void sendHealthReminder(String to, String ownerName, String petName,
                                   String vaccine, String dueOn, boolean overdue,
                                   String shelterName) {
        String subject = overdue
                ? petName + "'s " + vaccine + " vaccination is overdue"
                : petName + "'s " + vaccine + " vaccination is due soon";

        String lead = overdue
                ? "<p><strong>" + escape(petName) + "'s " + escape(vaccine)
                  + " vaccination was due on " + escape(dueOn) + "</strong> and has not been recorded yet.</p>"
                : "<p>" + escape(petName) + "'s " + escape(vaccine)
                  + " vaccination is due on <strong>" + escape(dueOn) + "</strong>.</p>";

        send(to, subject, layout(
                overdue ? "A check-up is overdue" : "A check-up is coming up",
                "<p>Hi " + escape(ownerName) + ",</p>" + lead
                + "<p>Booking it in with " + escape(shelterName)
                + " keeps " + escape(petName) + "'s record complete.</p>",
                "View " + escape(petName) + "'s health record", publicUrl + "/app/boarding",
                "You're getting this because you adopted " + escape(petName)
                + " through PawHome. You can turn these off in Settings &rarr; Notifications."));
    }

    // ------------------------------------------------------------------ layout

    /** One template, so every message looks like it came from the same place. */
    private String layout(String heading, String bodyHtml, String ctaLabel,
                          String ctaHref, String footnote) {
        return """
            <div style="margin:0;padding:24px;background:#FDF7FE;font-family:-apple-system,
                        BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
              <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:20px;
                          padding:32px;border:1px solid #F4E3F8;">
                <p style="margin:0 0 24px;font-size:20px;font-weight:600;color:#B20BF4;">PawHome</p>
                <h1 style="margin:0 0 16px;font-size:22px;line-height:1.3;color:#1A1420;">%s</h1>
                <div style="font-size:15px;line-height:1.6;color:#4B4453;">%s</div>
                <p style="margin:28px 0;">
                  <a href="%s" style="display:inline-block;background:#B20BF4;color:#ffffff;
                     text-decoration:none;padding:14px 28px;border-radius:999px;
                     font-size:15px;font-weight:500;">%s</a>
                </p>
                <p style="margin:0 0 8px;font-size:13px;color:#8A7F93;">
                  If the button doesn't work, paste this into your browser:</p>
                <p style="margin:0 0 24px;font-size:12px;color:#8A7F93;word-break:break-all;">%s</p>
                <p style="margin:0;padding-top:20px;border-top:1px solid #F4E3F8;
                          font-size:13px;line-height:1.6;color:#8A7F93;">%s</p>
              </div>
            </div>
            """.formatted(heading, bodyHtml, ctaHref, ctaLabel, ctaHref, footnote);
    }

    /**
     * Escape anything a user chose.
     *
     * A pet called <script>… is not a realistic attack on an email client, but
     * a name containing an ampersand or an angle bracket absolutely is, and it
     * would silently mangle the message. Escaping handles both.
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
