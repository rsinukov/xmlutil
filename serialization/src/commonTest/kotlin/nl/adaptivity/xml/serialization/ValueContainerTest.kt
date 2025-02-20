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

package nl.adaptivity.xml.serialization

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ValueContainerTest : TestBase<ValueContainerTest.ValueContainer>(
    ValueContainer("<foo&bar>"),
    ValueContainer.serializer()
) {
    override val expectedXML: String = "<valueContainer>&lt;foo&amp;bar></valueContainer>"
    override val expectedJson: String = "{\"content\":\"<foo&bar>\"}"

    @Test
    fun testAlternativeXml() {
        val alternativeXml = "<valueContainer><![CDATA[<foo&]]>bar&gt;</valueContainer>"
        assertEquals(value, baseXmlFormat.decodeFromString(serializer, alternativeXml))
    }

    @Test
    fun testAlternativeXml2() {
        val alternativeXml = "<valueContainer>&lt;foo&amp;bar&gt;</valueContainer>"
        assertEquals(value, baseXmlFormat.decodeFromString(serializer, alternativeXml))
    }

    @Serializable
    @XmlSerialName("valueContainer")
    data class ValueContainer(@XmlValue val content:String)

}
