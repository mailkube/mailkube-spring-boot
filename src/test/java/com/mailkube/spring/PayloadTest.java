package com.mailkube.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mailkube.model.SendEmailParams;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;

/** The mapping, as a unit. */
class PayloadTest {

    private static SimpleMailMessage minimal() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("hello@acme.test");
        message.setTo("customer@example.test");
        message.setSubject("Hello world");
        return message;
    }

    @Test
    void mapsTheRequiredFields() {
        SendEmailParams params = MailkubePayload.from(minimal());

        assertThat(params.body())
                .containsEntry("from", "hello@acme.test")
                .containsEntry("to", List.of("customer@example.test"))
                .containsEntry("subject", "Hello world");
    }

    @Test
    void mapsEveryOptionalFieldSimpleMailMessageCarries() {
        SimpleMailMessage message = minimal();
        message.setCc("cc@example.test");
        message.setBcc("bcc@example.test");
        message.setReplyTo("reply@acme.test");
        message.setText("Plain body");

        SendEmailParams params = MailkubePayload.from(message);

        assertThat(params.body())
                .containsEntry("cc", List.of("cc@example.test"))
                .containsEntry("bcc", List.of("bcc@example.test"))
                .containsEntry("reply_to", List.of("reply@acme.test"))
                .containsEntry("text", "Plain body");
    }

    @Test
    void omitsUnsetFieldsRatherThanSendingThemNull() {
        // The contract's "an unset setting is omitted, not passed as null". A null in the body is a
        // different request from an absent key, and it defeats the API's own defaulting.
        SendEmailParams params = MailkubePayload.from(minimal());

        assertThat(params.body()).doesNotContainKeys("cc", "bcc", "reply_to", "text", "html");
    }

    @Test
    void omitsAnEmptyRecipientArrayRatherThanSendingAnEmptyList() {
        // An empty array is not the same as an absent field, and Spring lets a caller set one.
        SimpleMailMessage message = minimal();
        message.setCc(new String[0]);

        assertThat(MailkubePayload.from(message).body()).doesNotContainKey("cc");
    }

    @Test
    void dropsNullAndBlankAddressesTheFrameworkDoesNotPolice() {
        // SimpleMailMessage arrays are caller-supplied and unchecked, so a stray null would reach
        // JSON as a null recipient and fail the request with an error naming the wrong thing.
        SimpleMailMessage message = minimal();
        message.setCc(new String[] {"real@example.test", null, "  "});

        assertThat(MailkubePayload.from(message).body()).containsEntry("cc", List.of("real@example.test"));
    }

    @Test
    void treatsAnAbsentSubjectAsEmptyRatherThanFailing() {
        SimpleMailMessage message = minimal();
        message.setSubject(null);

        assertThat(MailkubePayload.from(message).body()).containsEntry("subject", "");
    }

    @Test
    void rejectsAMissingFromAsAMailException() {
        // MailParseException, not IllegalArgumentException: every framework feature built on
        // Spring's mail hierarchy catches MailException, and would miss anything else.
        SimpleMailMessage message = minimal();
        message.setFrom(null);

        assertThatThrownBy(() -> MailkubePayload.from(message))
                .isInstanceOf(MailParseException.class)
                .hasMessageContaining("from");
    }

    @Test
    void rejectsMissingRecipientsAsAMailException() {
        SimpleMailMessage message = minimal();
        message.setTo(new String[0]);

        assertThatThrownBy(() -> MailkubePayload.from(message))
                .isInstanceOf(MailParseException.class)
                .hasMessageContaining("to");
    }

    @Test
    void mapsManyMessagesInOrder() {
        SimpleMailMessage first = minimal();
        SimpleMailMessage second = minimal();
        second.setSubject("Second");

        List<SendEmailParams> params = MailkubePayload.from(first, second);

        assertThat(params).hasSize(2);
        assertThat(params.get(1).body()).containsEntry("subject", "Second");
    }

    @Test
    void mapsNoMessagesToAnEmptyList() {
        assertThat(MailkubePayload.from(new SimpleMailMessage[0])).isEmpty();
    }

    @Test
    void mapsANullMessageArrayToAnEmptyList() {
        assertThat(MailkubePayload.from((SimpleMailMessage[]) null)).isEmpty();
    }

    @Test
    void rejectsABlankFromAsFirmlyAsAMissingOne() {
        // Blank and null are separate branches, and a whitespace-only address is the one a config
        // file actually produces.
        SimpleMailMessage message = minimal();
        message.setFrom("   ");

        assertThatThrownBy(() -> MailkubePayload.from(message))
                .isInstanceOf(MailParseException.class)
                .hasMessageContaining("from");
    }

    @Test
    void rejectsRecipientsThatAreAllBlank() {
        SimpleMailMessage message = minimal();
        message.setTo(new String[] {"  ", null});

        assertThatThrownBy(() -> MailkubePayload.from(message))
                .isInstanceOf(MailParseException.class)
                .hasMessageContaining("to");
    }

    @Test
    void omitsAReplyToThatWasNeverSet() {
        // The reply-to path wraps a single nullable value in an array before the emptiness check,
        // so the null case is its own branch.
        assertThat(MailkubePayload.from(minimal()).body()).doesNotContainKey("reply_to");
    }
}
