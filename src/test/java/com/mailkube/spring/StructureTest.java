package com.mailkube.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The structural invariants the compiler will not enforce and review keeps missing.
 *
 * <p>These assert about the SOURCE and the packaging rather than about behaviour, because what they
 * catch only appears in a consumer's application: a starter that never activates, a version that
 * travels as a placeholder, or a package that collides with the SDK's.
 */
class StructureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void registersTheAutoConfigurationWhereBootActuallyLooks() throws IOException {
        // `spring.factories` is gone in Boot 3+, and this file is the replacement. Get the path or
        // the class name wrong and NOTHING happens: no error, no bean, just a starter that silently
        // does nothing in every consumer's application.
        Path imports = Path.of(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

        assertThat(imports).exists();
        assertThat(Files.readString(imports).strip()).isEqualTo("com.mailkube.spring.MailkubeAutoConfiguration");
    }

    @Test
    void keepsTheRegistrationOnTheResourcePathRatherThanTheSourcePath() {
        // Under src/main/java, Gradle's source-set filter drops non-.java files from the jar
        // entirely, so the file would exist in the tree and be absent from the artifact.
        assertThat(Path.of("src/main/java/META-INF")).doesNotExist();
    }

    @Test
    void declaresEveryClassInTheStartersOwnSubpackage() throws IOException {
        // The SDK permanently claims `com.mailkube` and the packages directly under it. A type
        // declared there from this jar is a split package: a hard error on the module path, and a
        // duplicate-class hazard on the classpath. See .rules/SPRING_BOOT_INTEGRATION.md.
        for (Path source : mainSources()) {
            assertThat(Files.readString(source)).as("%s", source).contains("package com.mailkube.spring;");
        }
    }

    @Test
    void readsItsOwnVersionRatherThanTheSdks() throws IOException {
        // The suffix has to carry THIS artifact's version. Importing the SDK's Version class is the
        // easy mistake and it is invisible: the header still looks well-formed, it just reports the
        // wrong thing, and every conclusion drawn from the traffic afterwards is wrong with it.
        String autoConfiguration = stripComments(
                Files.readString(SOURCE_ROOT.resolve("com/mailkube/spring/MailkubeAutoConfiguration.java")));

        assertThat(autoConfiguration).contains("Version.current()").doesNotContain("com.mailkube.Version");
    }

    @Test
    void carriesNoVersionLiteralOutsideTheOneSource() throws IOException {
        // The version-literal bug appeared in three sibling templates independently.
        //
        // Comments are stripped before matching, and that is not a loophole: prose NAMING a version
        // is how the deviations in this tree are justified (which Boot line moved a class, which
        // spring-core first parsed Java 25). What must not exist is a literal in CODE, because that
        // is the one that ends up on the wire.
        //
        // Version.java is the single exception, and it is the reason the rule can exist at all: it
        // owns the documented `UNKNOWN` placeholder reported when neither the generated resource nor
        // the manifest names a version. Exempting the ONE file that defines the fallback is what
        // lets every other file be held to "no literal, ever".
        for (Path source : mainSources()) {
            if (source.endsWith("Version.java")) {
                continue;
            }
            assertThat(stripComments(Files.readString(source)))
                    .as("%s", source)
                    .doesNotContainPattern("\"\\d+\\.\\d+\\.\\d+\"");
        }
    }

    @Test
    void keepsTheOnlyVersionLiteralAsTheDocumentedPlaceholder() {
        // The exemption above is only safe while Version.java's literal IS the placeholder.
        assertThat(Version.UNKNOWN).isEqualTo("0.0.0");
    }

    /** Remove block and line comments, so these assertions read code rather than prose. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void ordersItselfAheadOfBootsMailConfigurationByNameNotByClass() throws IOException {
        // Boot 4 moved MailSenderAutoConfiguration to a different package AND a different jar, so a
        // `before = ...class` reference cannot compile against both lines and is absent entirely on
        // a Boot 4 application without the mail starter. Verified against 3.4, 3.5 and 4.0.
        String source = Files.readString(SOURCE_ROOT.resolve("com/mailkube/spring/MailkubeAutoConfiguration.java"));

        assertThat(source)
                .contains("beforeName")
                .contains("org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration")
                .contains("org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration");
    }

    @Test
    void neverImplementsJavaMailSender() throws IOException {
        // Implementing it would require createMimeMessage() and send(MimeMessage...), which means a
        // jakarta.mail dependency and parsing MIME back into fields — the second wire format the
        // integration contract forbids.
        //
        // Comments stripped for the same reason as above: the Javadoc on the sender EXPLAINS why
        // this starter is not a JavaMailSender, and a naive text scan would forbid documenting the
        // very decision it exists to protect.
        for (Path source : mainSources()) {
            assertThat(stripComments(Files.readString(source)))
                    .as("%s", source)
                    .doesNotContain("JavaMailSender")
                    .doesNotContain("jakarta.mail");
        }
    }

    @Test
    void resolvesTheVersionFromTheGeneratedResourceWhenThereIsOne() {
        InputStream resource = new ByteArrayInputStream("version=9.9.9".getBytes(StandardCharsets.UTF_8));

        assertThat(Version.resolve(resource, "1.1.1")).isEqualTo("9.9.9");
    }

    @Test
    void fallsBackToTheManifestWhenTheResourceIsAbsent() {
        assertThat(Version.resolve(null, "1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void reportsThePlaceholderWhenNeitherSourceNamesAVersion() {
        assertThat(Version.resolve(null, null)).isEqualTo(Version.UNKNOWN);
        assertThat(Version.resolve(null, "  ")).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void fallsBackToTheManifestWhenTheResourceNamesNoVersion() {
        // A properties file that loads cleanly but carries no `version` key. This is not a
        // hypothetical: the resource is generated, so a rename of the property would produce
        // exactly this file, and silently reporting an empty User-Agent token is worse than
        // falling through to the manifest.
        InputStream resource = new ByteArrayInputStream("other=9.9.9".getBytes(StandardCharsets.UTF_8));

        assertThat(Version.resolve(resource, "1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void fallsBackToTheManifestWhenTheResourceNamesABlankVersion() {
        InputStream resource = new ByteArrayInputStream("version=   ".getBytes(StandardCharsets.UTF_8));

        assertThat(Version.resolve(resource, "1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void ignoresAnUnreadableVersionResourceRatherThanFailingASend() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }
        };

        assertThat(Version.resolve(broken, "1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void reportsAVersionAtRuntime() {
        assertThat(Version.current()).isNotBlank();
    }
}
