package com.mailkube.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mailkube.MailkubeClient;
import com.mailkube.exception.ConnectionException;
import com.mailkube.model.Email;
import com.mailkube.model.SendEmailParams;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;

/**
 * The sender, end to end over the real SDK.
 *
 * <p>Every test here runs the SDK's real request building, JSON encoding and response parsing
 * against {@link StubServer}. Faking the client would only prove these tests agree with themselves.
 */
class MailSenderTest {

    private StubServer server;
    private MailkubeClient client;
    private MailkubeMailSender sender;

    @BeforeEach
    void start() {
        server = new StubServer();
        client = MailkubeClient.builder()
                .apiKey("mk_test")
                .baseUrl(server.baseUrl())
                .build();
        sender = new MailkubeMailSender(client);
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
    }

    private static SimpleMailMessage message() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("hello@acme.test");
        message.setTo("customer@example.test");
        message.setSubject("Hello world");
        message.setText("Body");
        return message;
    }

    @Test
    void sendsASimpleMessageThroughTheSdk() {
        sender.send(message());

        assertThat(server.received()).hasSize(1);
        assertThat(server.only().method()).isEqualTo("POST");
        assertThat(server.only().body()).contains("customer@example.test").contains("Hello world");
    }

    @Test
    void sendsTheFullFidelityParametersUnchanged() {
        // The native path: HTML and headers cannot be expressed by SimpleMailMessage at all, which
        // is the reason this surface exists alongside MailSender.
        Email email = sender.send(SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "Rich")
                .html("<p>Hi</p>")
                .build());

        assertThat(email.id()).isEqualTo("email_123");
        assertThat(server.only().body()).contains("<p>Hi</p>");
    }

    @Test
    void sendsEveryMessageInABatchAndPreservesOrder() {
        SimpleMailMessage second = message();
        second.setSubject("Second");

        sender.send(message(), second);

        assertThat(server.received()).hasSize(2);
        assertThat(server.received().get(1).body()).contains("Second");
    }

    @Test
    void sendsNothingForAnEmptyBatch() {
        sender.send(new SimpleMailMessage[0]);

        assertThat(server.received()).isEmpty();
    }

    @Test
    void identifiesTheStarterInTheUserAgentAfterTheSdksOwnToken() {
        // The contract requires the SDK's token to lead and this package's to trail. Asserted by
        // shape rather than by literal: pinning the SDK's version here would make this test fail on
        // an unrelated SDK bump, which teaches people to edit the assertion.
        new MailkubeMailSender(MailkubeClient.builder()
                        .apiKey("mk_test")
                        .baseUrl(server.baseUrl())
                        .userAgentSuffix("mailkube-spring-boot/" + Version.current())
                        .build())
                .send(message());

        String userAgent = server.only().header("User-Agent");
        assertThat(userAgent).matches("^mailkube-java/\\S+ mailkube-spring-boot/\\S+$");
    }

    @Test
    void translatesAnAuthenticationFailureIntoSpringsOwnType() {
        // Asserted on the native path, where the translation is directly observable. The
        // SimpleMailMessage path deliberately wraps every per-message failure in a MailSendException
        // so the caller can see WHICH messages failed, which would hide the translated type one
        // level down — that wrapping is covered by its own test below.
        server.reply(403, "{\"error\":{\"name\":\"invalid_api_key\",\"message\":\"nope\"}}");

        assertThatThrownBy(() ->
                        sender.send(SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "x")
                                .build()))
                .isInstanceOf(MailAuthenticationException.class);
    }

    @Test
    void translatesATransportFailureThatCarriesNoResponseAtAll() {
        // A ConnectionException is the one SDK failure OUTSIDE the ApiException hierarchy, so it is
        // the only case that exercises the "no request id to report" side of the description. It is
        // also the README's "everything else" row, and the taxonomy is the SDK's to own: this
        // starter must map it without inventing a category for it.
        server.close();

        assertThatThrownBy(() ->
                        sender.send(SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "x")
                                .build()))
                .isInstanceOf(MailSendException.class)
                .cause()
                .isInstanceOf(ConnectionException.class);
    }

    @Test
    void keepsTheTranslatedTypeVisibleInsideTheBatchWrapper() {
        server.reply(403, "{\"error\":{\"name\":\"invalid_api_key\",\"message\":\"nope\"}}");

        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(MailSendException.class)
                .extracting(e -> ((MailSendException) e)
                        .getFailedMessages()
                        .values()
                        .iterator()
                        .next())
                .isInstanceOf(MailAuthenticationException.class);
    }

    @Test
    void translatesAnApiFailureIntoAMailException() {
        // The invisible failure this pins: spring-retry, Spring Integration's mail adapters and
        // every @ControllerAdvice handler are written against MailException, so an escaping SDK
        // exception is caught by none of them and the mail silently does not send.
        server.reply(500, "{\"error\":{\"name\":\"server_error\",\"message\":\"boom\"}}");

        assertThatThrownBy(() ->
                        sender.send(SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "x")
                                .build()))
                .isInstanceOf(MailException.class)
                .hasCauseInstanceOf(com.mailkube.exception.ServerException.class);
    }

    @Test
    void namesTheFailedMessagesRatherThanImplyingTheBatchWasAtomic() {
        // The API has no batch transaction, so a partial failure is real and the caller has to be
        // able to tell which messages did not go. MailSendException's map is that contract.
        server.reply(500, "{\"error\":{\"name\":\"server_error\",\"message\":\"boom\"}}");
        SimpleMailMessage failing = message();

        assertThatThrownBy(() -> sender.send(failing))
                .isInstanceOf(MailSendException.class)
                .satisfies(e ->
                        assertThat(((MailSendException) e).getFailedMessages()).containsKey(failing));
    }

    @Test
    void reportsAnUnmappableMessageWithoutCallingTheApi() {
        SimpleMailMessage broken = message();
        broken.setFrom(null);

        assertThatThrownBy(() -> sender.send(broken))
                .isInstanceOf(MailSendException.class)
                .satisfies(e -> assertThat(
                                ((MailSendException) e).getFailedMessages().values())
                        .allSatisfy(cause -> assertThat(cause).isInstanceOf(MailParseException.class)));
        assertThat(server.received()).isEmpty();
    }

    @Test
    void sendsNothingForANullBatch() {
        // MailSender's varargs signature lets a caller pass a literal null array.
        sender.send((SimpleMailMessage[]) null);

        assertThat(server.received()).isEmpty();
    }

    @Test
    void namesTheRequestIdWhenTheApiSuppliedOne() {
        // The request id is the single most useful thing to have when asking about a failed send,
        // and it is lost the moment the exception is wrapped unless it is put in the message.
        //
        // It comes from the X-Request-Id RESPONSE HEADER, not from the error body — verified in the
        // SDK's transport rather than assumed, because a body field would look equally plausible
        // here and would silently never populate.
        server.replyWithHeader(
                500, "{\"error\":{\"name\":\"server_error\",\"message\":\"boom\"}}", "X-Request-Id", "req_abc");

        assertThatThrownBy(() ->
                        sender.send(SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "x")
                                .build()))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("req_abc");
    }

    @Test
    void translatesAConfigurationFailureIntoANonRetryableType() {
        // A misconfigured client cannot be fixed by retrying, which is what MailPreparationException
        // signals to a caller that distinguishes them. Built with no key and no environment so the
        // SDK's own constructor validation is what fires.
        MailkubeMailSender misconfigured = new MailkubeMailSender(
                () -> MailkubeClient.builder().environment(java.util.Map.of()).build());

        assertThatThrownBy(() -> misconfigured.send(
                        SendEmailParams.builder("hello@acme.test", List.of("customer@example.test"), "x")
                                .build()))
                .isInstanceOf(org.springframework.mail.MailPreparationException.class);
    }

    @Test
    void exposesTheClientItWraps() {
        assertThat(sender.client()).isSameAs(client);
    }

    @Test
    void sendsAListOfParameterSetsInOrder() {
        sender.send(List.of(
                SendEmailParams.builder("hello@acme.test", List.of("a@example.test"), "One")
                        .build(),
                SendEmailParams.builder("hello@acme.test", List.of("b@example.test"), "Two")
                        .build()));

        assertThat(server.received()).hasSize(2);
        assertThat(server.received().get(1).body()).contains("Two");
    }
}
