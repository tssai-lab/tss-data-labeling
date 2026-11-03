package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetWorkspaceTextFilePolicyTest {

    private final DatasetWorkspaceTextFilePolicy policy =
            new DatasetWorkspaceTextFilePolicy(new ObjectMapper());

    @Test
    void acceptsExactlyOneMiBAndRejectsLargerInlineText() {
        var accepted = policy.validate(
                "a".repeat(DatasetWorkspaceTextFilePolicy.MAX_INLINE_BYTES),
                "notes.txt",
                "txt",
                "text/plain"
        );

        assertEquals(
                DatasetWorkspaceTextFilePolicy.MAX_INLINE_BYTES,
                accepted.bytes().length
        );

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> policy.validate(
                        "a".repeat(
                                DatasetWorkspaceTextFilePolicy.MAX_INLINE_BYTES + 1
                        ),
                        "notes.txt",
                        "txt",
                        "text/plain"
                )
        );
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatus());
        assertEquals("INLINE_TEXT_TOO_LARGE", error.getErrorCode());
    }

    @Test
    void validatesJsonMimeAndSafeFileNames() {
        V2BusinessException syntax = assertThrows(
                V2BusinessException.class,
                () -> policy.validate("{", "labels.json", "json", "application/json")
        );
        assertEquals("INLINE_TEXT_SYNTAX_INVALID", syntax.getErrorCode());

        V2BusinessException mime = assertThrows(
                V2BusinessException.class,
                () -> policy.validate(
                        "{}",
                        "labels.json",
                        "json",
                        "text/plain"
                )
        );
        assertEquals("RESOURCE_CONTENT_TYPE_INVALID", mime.getErrorCode());

        V2BusinessException path = assertThrows(
                V2BusinessException.class,
                () -> policy.validate("x", "../notes.txt", "txt", "text/plain")
        );
        assertEquals("INVALID_REQUEST", path.getErrorCode());
    }

    @Test
    void rejectsDoctypeAndNulContent() {
        V2BusinessException xml = assertThrows(
                V2BusinessException.class,
                () -> policy.validate(
                        "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                                + "<x>&e;</x>",
                        "labels.xml",
                        "xml",
                        "application/xml"
                )
        );
        assertEquals("INLINE_TEXT_SYNTAX_INVALID", xml.getErrorCode());

        V2BusinessException nul = assertThrows(
                V2BusinessException.class,
                () -> policy.validate("a\0b", "notes.txt", "txt", "text/plain")
        );
        assertEquals("INVALID_REQUEST", nul.getErrorCode());
    }
}
