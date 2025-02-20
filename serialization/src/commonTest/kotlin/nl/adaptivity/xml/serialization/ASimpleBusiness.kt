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
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

class ASimpleBusiness : TestBase<ASimpleBusiness.Business>(
    Business("ABC Corp", Address("1", "ABC road", "ABCVille")),
    Business.serializer()
) {
    override val expectedXML: String =
        "<Business name=\"ABC Corp\"><headOffice houseNumber=\"1\" street=\"ABC road\" city=\"ABCVille\" status=\"VALID\"/></Business>"
    override val expectedJson: String =
        "{\"name\":\"ABC Corp\",\"headOffice\":{\"houseNumber\":\"1\",\"street\":\"ABC road\",\"city\":\"ABCVille\",\"status\":\"VALID\"}}"

    enum class AddresStatus { VALID, INVALID, TEMPORARY }

    @Serializable
    @XmlSerialName("address")
    data class Address(
        val houseNumber: String,
        val street: String,
        val city: String,
        @XmlElement(false) val status: AddresStatus = AddresStatus.VALID
    )

    @Serializable
    data class Business(val name: String, @XmlSerialName("headOffice") val headOffice: Address?)

}
