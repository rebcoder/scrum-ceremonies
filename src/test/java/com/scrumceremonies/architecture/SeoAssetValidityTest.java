package com.scrumceremonies.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The crawler-facing static assets are machine-read by Google, not by a human, so a
 * syntax error in them is silent: the file still serves 200, still looks right in an
 * editor, and is simply rejected by the consumer.
 *
 * <p>{@code sitemap.xml} in particular is easy to break invisibly: a comment or blank
 * line placed above the XML declaration is enough, since {@code <?xml ...?>} must be
 * the very first thing in the document, and every parser rejects the file with "XML or
 * text declaration not at start of entity" if it isn't. Nothing about serving the file
 * would reveal that on its own.
 *
 * <p>These assertions parse the assets rather than pattern-matching them: a regex for
 * {@code <loc>} would pass happily on a file broken in exactly this way, which is the
 * failure mode this guards against.
 */
@DisplayName("crawler-facing SEO assets are syntactically valid")
class SeoAssetValidityTest {

    private static final Path V1 = Path.of("frontend", "v1");
    private static final Path SITEMAP = V1.resolve("sitemap.xml");
    private static final Path ROBOTS = V1.resolve("robots.txt");

    /** Pages that must stay crawlable; the floor also stops an empty sweep reporting clean. */
    private static final int MIN_SITEMAP_URLS = 5;

    @Test
    @DisplayName("sitemap.xml parses as XML — the declaration must lead the document")
    void sitemapParses() {
        assertThat(SITEMAP).as("sitemap.xml is missing").exists();

        assertThatCode(() -> {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(SITEMAP.toFile());
        }).as("""
                sitemap.xml does not parse. The usual cause is a comment or blank line placed \
                BEFORE the <?xml ...?> declaration — the declaration must be the very first \
                thing in the document. Put provenance comments on the line AFTER it.""")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every sitemap <loc> is an absolute URL, and there are enough of them")
    void sitemapUrlsAreAbsolute() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(SITEMAP.toFile());

        NodeList locs = doc.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");

        List<String> relative = new ArrayList<>();
        for (int i = 0; i < locs.getLength(); i++) {
            String url = locs.item(i).getTextContent().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                relative.add(url);
            }
        }

        assertThat(locs.getLength())
                .as("sitemap resolved %d <loc> entries; a sweep over nothing reports clean",
                        locs.getLength())
                .isGreaterThanOrEqualTo(MIN_SITEMAP_URLS);

        assertThat(relative)
                .as("sitemaps require absolute URLs; these are relative:%n%s", relative)
                .isEmpty();
    }

    @Test
    @DisplayName("robots.txt allows crawling and keeps /actuator/ out")
    void robotsAllowsCrawlingAndBlocksActuator() throws IOException {
        assertThat(ROBOTS).as("robots.txt is missing").exists();
        String robots = Files.readString(ROBOTS, StandardCharsets.UTF_8);

        assertThat(robots)
                .as("robots.txt must declare a user-agent group")
                .containsIgnoringCase("User-agent:");

        assertThat(robots)
                .as("""
                        robots.txt must not blanket-disallow the site — these are public marketing \
                        pages whose whole point is to be found.""")
                .doesNotContain("Disallow: /\n");

        assertThat(robots)
                .as("the actuator endpoints are internal and must stay out of the index")
                .contains("Disallow: /actuator/");
    }
}
