/*
 * Copyright (c) 2021.
 *
 * This file is part of xmlutil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.pdvrieze.xmlutil.testutil

import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals


@OptIn(ExperimentalXmlUtilApi::class)
fun assertXmlEquals(expected: String, actual: String) {
    if (expected != actual) {
        val expectedReader = KtXmlReader(StringReader(expected)).apply { skipPreamble() }
        val actualReader = KtXmlReader(StringReader(actual)).apply { skipPreamble() }

        try {
            assertXmlEquals(expectedReader, actualReader)
        } catch (e: AssertionError) {
            try {
                assertEquals(expected, actual)
            } catch (f: AssertionError) {
                f.addSuppressed(e)
                throw f
            }
        }
    }
}

fun XmlReader.nextNotIgnored() {
    do {
        val ev = next()
    } while (ev.isIgnorable && hasNext())
}

fun assertXmlEquals(expected: XmlReader, actual: XmlReader): Unit {
    do {
        expected.nextNotIgnored()
        actual.nextNotIgnored()

        assertXmlEquals(expected.toEvent(), actual.toEvent())

    } while (expected.eventType != EventType.END_DOCUMENT && expected.hasNext() && actual.hasNext())

    while (expected.hasNext() && expected.isIgnorable()) { expected.next() }
    while (actual.hasNext() && actual.isIgnorable()) { actual.next() }

    assertEquals(expected.hasNext(), actual.hasNext())
}

fun assertXmlEquals(expectedEvent: XmlEvent, actualEvent: XmlEvent) {
    assertEquals(expectedEvent.eventType, actualEvent.eventType, "Different event found")
    when (expectedEvent) {
        is XmlEvent.StartElementEvent -> assertStartElementEquals(expectedEvent, actualEvent as XmlEvent.StartElementEvent)
        is XmlEvent.EndElementEvent -> assertEquals(expectedEvent.name, (actualEvent as XmlEvent.EndElementEvent).name)
        is XmlEvent.TextEvent -> {
            if (! (expectedEvent.isIgnorable && actualEvent.isIgnorable)) {
                assertEquals(expectedEvent.text, (actualEvent as XmlEvent.TextEvent).text)
            }
        }
    }
}

fun assertStartElementEquals(expectedEvent: XmlEvent.StartElementEvent, actualEvent: XmlEvent.StartElementEvent) {
    assertEquals(expectedEvent.name, actualEvent.name)
    assertEquals(expectedEvent.attributes.size, actualEvent.attributes.size)

    val expectedAttrs = expectedEvent.attributes.map { XmlEvent.Attribute(it.namespaceUri, it.localName, "", it.value) }
        .sortedBy { "{${it.namespaceUri}}${it.localName}" }
    val actualAttrs = actualEvent.attributes.map { XmlEvent.Attribute(it.namespaceUri, it.localName, "", it.value) }
        .sortedBy { "{${it.namespaceUri}}${it.localName}" }

    assertContentEquals(expectedAttrs, actualAttrs)
}
