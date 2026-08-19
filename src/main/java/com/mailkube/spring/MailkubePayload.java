package com.mailkube.spring;

import com.mailkube.model.SendEmailParams;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;

/**
 * The one payload module: {@link SimpleMailMessage} in, {@link SendEmailParams} out.
 *
 * <p>Every entry point that starts from a Spring message calls this, so the mapping cannot disagree
 * with itself. The native send path deliberately does <b>not</b> route through here: it already
 * takes {@link SendEmailParams}, and forcing it through a converter would mean inventing a
 * lossy intermediate representation for values the caller has already expressed exactly. That is the
 * contract's "share the conversion they truly have in common and no more".
 *
 * <p><b>What {@code SimpleMailMessage} can carry is the whole story here</b>, and it is a short one:
 * from, to, cc, bcc, reply-to, subject, text, and a sent date. There is no HTML body, no attachment
 * and no custom header on the type, permanently. Mapping "everything the framework's message type
 * natively expresses" therefore lands on plain text only, and anything richer belongs on the native
 * path rather than in an invented side channel.
 */
public final class MailkubePayload {

    private MailkubePayload() {}

    /**
     * Convert a Spring mail message into SDK send parameters.
     *
     * @param message the message to convert
     * @return the SDK parameters
     * @throws MailParseException if the message omits a field the API requires
     */
    public static SendEmailParams from(SimpleMailMessage message) {
        // MailParseException, not IllegalArgumentException: a caller catching MailException — which
        // is every framework feature built on Spring's mail hierarchy — must see this too. Letting
        // an unchecked non-MailException escape is the invisible failure the contract's error clause
        // is about.
        String from = require(message.getFrom(), "from");
        List<String> to = requireRecipients(message.getTo());
        String subject = message.getSubject() == null ? "" : message.getSubject();

        SendEmailParams.Builder builder = SendEmailParams.builder(from, to, subject);
        // Unset is omitted, never passed as an empty list: the SDK omits an absent field from the
        // wire body entirely, and an empty array is a different request.
        applyIfPresent(message.getCc(), builder::cc);
        applyIfPresent(message.getBcc(), builder::bcc);
        // Reply-To is a single value on SimpleMailMessage and a list on the SDK, so it goes through
        // the same emptiness check as the arrays rather than a second code path.
        applyIfPresent(new String[] {message.getReplyTo()}, builder::replyTo);
        if (message.getText() != null) {
            builder.text(message.getText());
        }
        return builder.build();
    }

    private static void applyIfPresent(String[] values, Consumer<List<String>> setter) {
        List<String> cleaned = clean(values);
        if (!cleaned.isEmpty()) {
            setter.accept(cleaned);
        }
    }

    /**
     * Drop nulls and blanks from an address array.
     *
     * <p>{@code SimpleMailMessage} arrays are caller-supplied and Spring does not police them, so a
     * stray null would otherwise reach JSON serialization as a null recipient and fail the request
     * with an error naming the wrong thing.
     */
    private static List<String> clean(String[] values) {
        if (values == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(values.length);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value);
            }
        }
        return cleaned;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MailParseException("A '" + field + "' address is required.");
        }
        return value;
    }

    private static List<String> requireRecipients(String[] values) {
        List<String> cleaned = clean(values);
        if (cleaned.isEmpty()) {
            throw new MailParseException("At least one 'to' recipient is required.");
        }
        return cleaned;
    }

    /**
     * Convert an array of messages, preserving order.
     *
     * @param messages the messages
     * @return the SDK parameters, in the same order
     * @throws MailParseException if any message omits a required field
     */
    public static List<SendEmailParams> from(SimpleMailMessage... messages) {
        return Arrays.stream(MailkubePayload.nullToEmpty(messages))
                .map(MailkubePayload::from)
                .toList();
    }

    private static SimpleMailMessage[] nullToEmpty(SimpleMailMessage[] messages) {
        return messages == null ? new SimpleMailMessage[0] : messages;
    }
}
