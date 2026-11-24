package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JsFailureTest {

    @Test public void theErrorTypeAndAMissingNameAreKept() {
        assertEquals(" js=ReferenceError missing=net", JsFailure.describe("ReferenceError: net is not defined\n    at init (https://scripts.test/a.js:12:3)"));
        assertEquals(" js=ReferenceError missing=dialog", JsFailure.describe("Uncaught ReferenceError: 'dialog' is not defined"));
        assertEquals(" js=TypeError notFunction=openStream", JsFailure.describe("TypeError: this.net.openStream is not a function"));
        assertEquals(" js=SyntaxError", JsFailure.describe("SyntaxError: unexpected token in expression: '?'"));
        assertEquals(" js=module", JsFailure.describe("could not load module 'https://scripts.test/lib/crypto.js'"));
    }

    @Test public void nothingFromTheMessageItselfLeaves() {
        // An address, a title or any other text of the message is never copied.
        assertEquals(" js=TypeError", JsFailure.describe("TypeError: cannot read property 'list' of https://private.test/api?token=abc 某部电影"));
        assertEquals(" js=Error", JsFailure.describe("Error: 登录失败 https://private.test"));
        assertEquals("", JsFailure.describe("https://private.test/a.js failed"));
        assertEquals("", JsFailure.describe(null));
        assertEquals("", JsFailure.describe(""));
        // Only a bare identifier counts as a name.
        assertEquals(" js=ReferenceError", JsFailure.describe("ReferenceError: https://private.test/x is not defined"));
    }
}
