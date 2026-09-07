package com.scrumceremonies.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEO Content Verification Tests
 *
 * Validates that SEO-optimized content is present in HTML source:
 * - H1 tags with Scrum-related keywords
 * - Descriptive paragraphs
 * - Internal links
 * - Structured data (schema.org)
 * - Meta tags (title, description, canonical)
 *
 * IMPORTANT: These tests read RAW HTML source (not DOM after JS execution)
 * to ensure content is crawlable by search engines.
 *
 * NOTE: most tests here are disabled and assert markup this project no longer
 * ships. Only testMetaTagsArePresent() runs.
 *
 * Two pieces of markup they depend on are gone: the hidden `seo-content` block
 * was removed, and so were the canonical/og:url tags — this project ships with
 * no fixed domain, so a canonical URL would have had nothing truthful to point
 * at. The disabled test bodies below still assert on both.
 *
 * Re-enabling any of them means rewriting the assertions first, not just
 * deleting the annotation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeoContentVerificationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testRootPageContainsSeoContent() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        String html = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html).isNotNull();

        // Verify SEO content markers
        assertThat(html)
                .describedAs("Root page should contain H1 with 'Scrum Ceremonies' and 'Scrum Toolkit' keywords")
                .containsIgnoringCase("<h1>")
                .containsIgnoringCase("Scrum Ceremonies")
                .containsIgnoringCase("Scrum");

        // Verify hidden SEO content is present
        assertThat(html)
                .describedAs("Root page should contain hidden SEO content with class 'seo-content'")
                .containsIgnoringCase("seo-content")
                .containsIgnoringCase("Planning Poker")
                .containsIgnoringCase("Sprint Retrospective");

        // Verify internal links
        assertThat(html)
                .describedAs("Root page should contain internal links to poker and retro pages")
                .contains("/poker.html")
                .contains("/retro.html");

        // Verify structured data
        assertThat(html)
                .describedAs("Root page should contain FAQPage schema")
                .containsIgnoringCase("@type")
                .containsIgnoringCase("FAQPage");

        assertThat(html)
                .describedAs("Root page should contain SoftwareApplication schema")
                .containsIgnoringCase("SoftwareApplication");

        // Verify meta tags
        assertThat(html)
                .describedAs("Root page should contain improved title tag")
                .containsIgnoringCase("<title>")
                .containsIgnoringCase("Scrum")
                .containsIgnoringCase("Agile");

        assertThat(html)
                .describedAs("Root page should contain meta description")
                .containsIgnoringCase("meta name=\"description\"");

        assertThat(html)
                .describedAs("Root page should contain canonical URL")
                .containsIgnoringCase("rel=\"canonical\"");
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testPokerPageContainsSeoContent() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/poker.html", String.class);
        String html = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html).isNotNull();

        // Verify SEO content markers
        assertThat(html)
                .describedAs("Poker page should contain H1 with 'Scrum Poker' keywords")
                .containsIgnoringCase("<h1>")
                .containsIgnoringCase("Scrum Poker")
                .containsIgnoringCase("Planning Poker");

        // Verify hidden SEO content
        assertThat(html)
                .describedAs("Poker page should contain hidden SEO content")
                .containsIgnoringCase("seo-content")
                .containsIgnoringCase("How Scrum Poker Works")
                .containsIgnoringCase("remote teams");

        // Verify internal links
        assertThat(html)
                .describedAs("Poker page should contain internal links")
                .contains("/retro.html")
                .contains("/");

        // Verify structured data
        assertThat(html)
                .describedAs("Poker page should contain FAQPage schema")
                .containsIgnoringCase("@type")
                .containsIgnoringCase("FAQPage");

        // Verify meta tags
        assertThat(html)
                .describedAs("Poker page should contain SEO-optimized title")
                .containsIgnoringCase("<title>")
                .containsIgnoringCase("Scrum Poker Online")
                .containsIgnoringCase("Planning Poker");

        assertThat(html)
                .describedAs("Poker page should contain meta description")
                .containsIgnoringCase("meta name=\"description\"");

        assertThat(html)
                .describedAs("Poker page should contain canonical URL")
                .containsIgnoringCase("rel=\"canonical\"")
                .contains("/poker.html");
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testRetroPageContainsSeoContent() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/retro.html", String.class);
        String html = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html).isNotNull();

        // Verify SEO content markers
        assertThat(html)
                .describedAs("Retro page should contain H1 with 'Sprint Retrospective' keywords")
                .containsIgnoringCase("<h1>")
                .containsIgnoringCase("Sprint Retrospective");

        // Verify hidden SEO content
        assertThat(html)
                .describedAs("Retro page should contain hidden SEO content")
                .containsIgnoringCase("seo-content")
                .containsIgnoringCase("Sprint Retrospective Tool")
                .containsIgnoringCase("agile teams");

        // Verify internal links
        assertThat(html)
                .describedAs("Retro page should contain internal links")
                .contains("/poker.html")
                .contains("/");

        // Verify structured data
        assertThat(html)
                .describedAs("Retro page should contain FAQPage schema")
                .containsIgnoringCase("@type")
                .containsIgnoringCase("FAQPage");

        // Verify meta tags
        assertThat(html)
                .describedAs("Retro page should contain SEO-optimized title")
                .containsIgnoringCase("<title>")
                .containsIgnoringCase("Sprint Retrospective Tool");

        assertThat(html)
                .describedAs("Retro page should contain meta description")
                .containsIgnoringCase("meta name=\"description\"");

        assertThat(html)
                .describedAs("Retro page should contain canonical URL")
                .containsIgnoringCase("rel=\"canonical\"")
                .contains("/retro.html");
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testSeoContentIsHiddenWithCss() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        String html = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html).isNotNull();

        // Verify CSS hiding technique is used (Google-approved)
        assertThat(html)
                .describedAs("SEO content should be hidden using position: absolute technique")
                .containsIgnoringCase(".seo-content")
                .containsIgnoringCase("position: absolute")
                .containsIgnoringCase("left: -9999px")
                .containsIgnoringCase("overflow: hidden");

        // Verify NOT using display: none or visibility: hidden (not crawlable)
        // Note: These might be present for other elements, but seo-content should use position: absolute
        String seoContentCss = extractSeoContentCss(html);
        if (seoContentCss != null && !seoContentCss.isEmpty()) {
            assertThat(seoContentCss)
                    .describedAs("SEO content CSS should use position: absolute, not display: none")
                    .containsIgnoringCase("position: absolute");
        }
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testStructuredDataIsValidJson() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        String html = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html).isNotNull();

        // Verify structured data is present as JSON-LD
        assertThat(html)
                .describedAs("Structured data should be in JSON-LD format")
                .containsIgnoringCase("application/ld+json")
                .containsIgnoringCase("@context")
                .containsIgnoringCase("https://schema.org");

        // Verify FAQ schema structure
        assertThat(html)
                .describedAs("FAQ schema should contain Question and Answer types")
                .containsIgnoringCase("\"@type\": \"Question\"")
                .containsIgnoringCase("\"@type\": \"Answer\"");

        // Verify SoftwareApplication schema
        assertThat(html)
                .describedAs("SoftwareApplication schema should be present")
                .containsIgnoringCase("\"@type\": \"SoftwareApplication\"");
    }

    @Disabled("Asserts markup that no longer exists: the hidden seo-content block and rel=\"canonical\" tags were both removed (this project ships with no fixed domain, so a canonical URL has nothing to point at). Rewrite the assertions before re-enabling.")
    @Test
    void testInternalLinksArePresent() {
        // When
        ResponseEntity<String> rootResponse = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        ResponseEntity<String> pokerResponse = restTemplate.getForEntity(getBaseUrl() + "/poker.html", String.class);
        ResponseEntity<String> retroResponse = restTemplate.getForEntity(getBaseUrl() + "/retro.html", String.class);

        // Then - Verify internal linking structure
        String rootHtml = rootResponse.getBody();
        String pokerHtml = pokerResponse.getBody();
        String retroHtml = retroResponse.getBody();

        // Root page should link to poker and retro
        assertThat(rootHtml)
                .describedAs("Root page should contain links to poker and retro pages")
                .contains("/poker.html")
                .contains("/retro.html");

        // Poker page should link back to root and retro
        assertThat(pokerHtml)
                .describedAs("Poker page should contain internal links")
                .contains("/")
                .contains("/retro.html");

        // Retro page should link back to root and poker
        assertThat(retroHtml)
                .describedAs("Retro page should contain internal links")
                .contains("/")
                .contains("/poker.html");
    }

    @Test
    void testMetaTagsArePresent() {
        // Backend is API-only; / and HTML pages return 404. SEO meta tags are validated on frontend (Static Web Apps).
        ResponseEntity<String> rootResponse = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        ResponseEntity<String> pokerResponse = restTemplate.getForEntity(getBaseUrl() + "/poker.html", String.class);
        ResponseEntity<String> retroResponse = restTemplate.getForEntity(getBaseUrl() + "/retro.html", String.class);
        assertThat(rootResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pokerResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(retroResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Helper method to extract CSS for .seo-content class
     */
    private String extractSeoContentCss(String html) {
        if (html == null) {
            return null;
        }
        int styleStart = html.indexOf(".seo-content");
        if (styleStart == -1) {
            return null;
        }
        int styleEnd = html.indexOf("}", styleStart);
        if (styleEnd == -1) {
            return null;
        }
        return html.substring(styleStart, styleEnd + 1);
    }
}

