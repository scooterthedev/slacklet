package com.scooter.slackwear.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamlFormTest {

    @Test
    fun aFormAndItsHiddenFieldsAreParsedOutOfTheSsoPage() {
        val form = Form.parse(
            """
            <html><body>
              <form action="/sso/saml/continue" method="post">
                <input type="hidden" name="SAMLResponse" value="base64blob">
                <input type="hidden" name="RelayState" value="rs-123">
                <input type="submit" value="Continue">
              </form>
            </body></html>
            """,
        )!!
        assertEquals("/sso/saml/continue", form.action)
        assertEquals("base64blob", form.fields["SAMLResponse"])
        assertEquals("rs-123", form.fields["RelayState"])
    }

    @Test
    fun anInputWithoutANameIsSkippedRatherThanPostedAsBlank() {
        val form = Form.parse(
            """<form action="/go"><input type="submit" value="Continue">
               <input name="token" value="t1"></form>""",
        )!!
        assertEquals(mapOf("token" to "t1"), form.fields)
    }

    @Test
    fun aNamedInputWithNoValueIsKeptAsEmptyRatherThanDropped() {
        val form = Form.parse("""<form action="/go"><input name="blank"></form>""")!!
        assertTrue(form.fields.containsKey("blank"))
        assertEquals("", form.fields["blank"])
    }

    @Test
    fun pageWithNoFormYieldsNothing() {
        assertNull(Form.parse("<html><body>no form here</body></html>"))
        assertNull(Form.parse(""))
    }

    @Test
    fun everyFormOnThePageIsAvailableAndTheFirstIsWhatParseReturns() {
        val html = """
            <form action="/first"><input name="a" value="1"></form>
            <form action="/second"><input name="b" value="2"></form>
        """
        val all = Form.findAll(html)
        assertEquals(listOf("/first", "/second"), all.map { it.action })
        assertEquals("/first", Form.parse(html)!!.action)
    }

    @Test
    fun aRelativeActionIsResolvedAgainstThePageItCameFrom() {
        val form = Form("/sso/continue", emptyMap())
        assertEquals(
            "https://example.enterprise.slack.com/sso/continue",
            form.resolveAction("https://example.enterprise.slack.com/sso/start?x=1"),
        )
    }

    @Test
    fun anAbsoluteActionIsLeftExactlyAsSlackSentIt() {
        val form = Form("https://idp.example.com/saml", emptyMap())
        assertEquals(
            "https://idp.example.com/saml",
            form.resolveAction("https://example.enterprise.slack.com/sso/start"),
        )
    }

    @Test
    fun aSiblingRelativeActionResolvesRelativeToTheCurrentPath() {
        val form = Form("continue", emptyMap())
        assertEquals(
            "https://example.com/sso/continue",
            form.resolveAction("https://example.com/sso/start"),
        )
    }

    @Test
    fun singleQuotedAttributesParseTheSameAsDoubleQuoted() {
        val form = Form.parse("""<form action='/go'><input name='token' value='t1'></form>""")!!
        assertEquals("/go", form.action)
        assertEquals("t1", form.fields["token"])
    }

    @Test
    fun aFormSpanningManyLinesIsStillMatched() {
        val form = Form.parse(
            "<form\n  action=\"/multi\"\n  method=\"post\">\n<input\n name=\"a\"\n value=\"1\">\n</form>",
        )!!
        assertEquals("/multi", form.action)
        assertEquals("1", form.fields["a"])
    }
}
