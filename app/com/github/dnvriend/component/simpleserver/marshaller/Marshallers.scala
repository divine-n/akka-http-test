/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dnvriend.component.simpleserver.marshaller

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.dnvriend.component.simpleserver.dto._
import com.github.dnvriend.component.simpleserver.dto.http._
import spray.json.{ DefaultJsonProtocol, _ }

import scala.concurrent.ExecutionContext
import scala.xml.{ Elem, NodeSeq, XML }

/**
 * See: http://liddellj.com/using-media-type-parameters-to-version-an-http-api/
 *
 * As a rule, when requesting `application/json` or `application/xml` you should return the latest version and should
 * be the same as the latest vendor media type.
 *
 * When a client requests a representation, using the vendor specific media type which includes a version, the API should
 * return that representation
 */
object MediaVersionTypes {
  def customMediatype(subType: String) = MediaType.customWithFixedCharset("application", subType, HttpCharsets.`UTF-8`)

  val `application/vnd.acme.v1+json` = customMediatype("vnd.acme.v1+json")
  val `application/vnd.acme.v2+json` = customMediatype("vnd.acme.v2+json")
  val `application/vnd.acme.v1+xml` = customMediatype("vnd.acme.v1+xml")
  val `application/vnd.acme.v2+xml` = customMediatype("vnd.acme.v2+xml")
}

object Marshallers extends Marshallers

trait Marshallers extends DefaultJsonProtocol with SprayJsonSupport with ScalaXmlSupport {
  implicit val personJsonFormatV1 = jsonFormat2(PersonV1)
  implicit val personJsonFormatV2 = jsonFormat3(PersonV2)
  implicit val pingJsonFormat = jsonFormat1(Ping)
  implicit val personJsonFormat = jsonFormat3(Person)
  implicit val orderDtoJsonFormat = jsonFormat2(OrderDto)

  def marshalPersonXmlV2(person: PersonV2): NodeSeq =
    <person>
      <name>
        { person.name }
      </name>
      <age>
        { person.age }
      </age>
      <married>
        { person.married }
      </married>
    </person>

  def marshalPersonsXmlV2(persons: Iterable[PersonV2]) =
    <persons>
      { persons.map(marshalPersonXmlV2) }
    </persons>

  def marshalPersonXmlV1(person: PersonV1): NodeSeq =
    <person>
      <name>
        { person.name }
      </name>
      <age>
        { person.age }
      </age>
    </person>

  def marshalPersonsXmlV1(persons: Iterable[PersonV1]) =
    <persons>
      { persons.map(marshalPersonXmlV1) }
    </persons>

  implicit def personsXmlFormatV1 = Marshaller.opaque[Iterable[PersonV1], NodeSeq](marshalPersonsXmlV1)

  implicit def personXmlFormatV1 = Marshaller.opaque[PersonV1, NodeSeq](marshalPersonXmlV1)

  implicit def personsXmlFormatV2 = Marshaller.opaque[Iterable[PersonV2], NodeSeq](marshalPersonsXmlV2)

  implicit def personXmlFormatV2 = Marshaller.opaque[PersonV2, NodeSeq](marshalPersonXmlV2)

  /**
   * From the Iterable[Person] value-object convert to a version and then marshal, wrap in an entity;
   * communicate with the VO in the API
   */
  implicit def personsMarshaller(implicit ec: ExecutionContext): ToResponseMarshaller[Iterable[Person]] = Marshaller.oneOf(
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { persons =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaTypes.`application/json`), persons.map(person => PersonV2(person.name, person.age, person.married)).toJson.compactPrint))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v1+json`) { persons =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaVersionTypes.`application/vnd.acme.v1+json`), persons.map(person => PersonV1(person.name, person.age)).toJson.compactPrint))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v2+json`) { persons =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaVersionTypes.`application/vnd.acme.v2+json`), persons.map(person => PersonV2(person.name, person.age, person.married)).toJson.compactPrint))
    },
    Marshaller.withOpenCharset(MediaTypes.`application/xml`) { (persons, charset) =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType.WithCharset(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
          Source.fromIterator(() => persons.iterator).mapAsync(1) { person =>
            Marshal(persons.map(person => PersonV2(person.name, person.age, person.married))).to[NodeSeq]
          }.map(ns => ByteString(ns.toString))
        ))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v1+xml`) { persons =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType(MediaVersionTypes.`application/vnd.acme.v1+xml`),
          Source.fromIterator(() => persons.iterator).mapAsync(1) { person =>
            Marshal(persons.map(person => PersonV1(person.name, person.age))).to[NodeSeq]
          }.map(ns => ByteString(ns.toString))
        ))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v2+xml`) { persons =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType(MediaVersionTypes.`application/vnd.acme.v2+xml`),
          Source.fromIterator(() => persons.iterator).mapAsync(1) { person =>
            Marshal(persons.map(person => PersonV2(person.name, person.age, person.married))).to[NodeSeq]
          }.map(ns => ByteString(ns.toString))
        ))
    }
  )

  /**
   * From the Person value-object convert to a version and then marshal, wrap in an entity;
   * communicate with the VO in the API
   */
  implicit def personMarshaller(implicit ec: ExecutionContext): ToResponseMarshaller[Person] = Marshaller.oneOf(
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { person =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaTypes.`application/json`), PersonV2(person.name, person.age, person.married).toJson.compactPrint))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v1+json`) { person =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaVersionTypes.`application/vnd.acme.v1+json`), PersonV1(person.name, person.age).toJson.compactPrint))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v2+json`) { person =>
      HttpResponse(entity =
        HttpEntity(ContentType(MediaVersionTypes.`application/vnd.acme.v2+json`), PersonV2(person.name, person.age, person.married).toJson.compactPrint))
    },
    Marshaller.withOpenCharset(MediaTypes.`application/xml`) { (person, charset) =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType.WithCharset(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
          Source.fromFuture(Marshal(PersonV2(person.name, person.age, person.married)).to[NodeSeq])
            .map(ns => ByteString(ns.toString))
        ))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v1+xml`) { person =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType(MediaVersionTypes.`application/vnd.acme.v1+xml`),
          Source.fromFuture(Marshal(PersonV1(person.name, person.age)).to[NodeSeq])
            .map(ns => ByteString(ns.toString))
        ))
    },
    Marshaller.withFixedContentType(MediaVersionTypes.`application/vnd.acme.v2+xml`) { person =>
      HttpResponse(entity =
        HttpEntity.CloseDelimited(
          ContentType(MediaVersionTypes.`application/vnd.acme.v2+xml`),
          Source.fromFuture(Marshal(PersonV2(person.name, person.age, person.married)).to[NodeSeq])
            .map(ns => ByteString(ns.toString))
        ))
    }
  )

  // curl -X POST -H "Content-Type: application/xml" -d '<person><name>John Doe</name><age>25</age><married>true</married></person>' localhost:8080/person
  def personXmlEntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaTypes.`application/xml`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val xml: Elem = XML.loadString(input)
      val name: String = (xml \\ "name").text
      val age: Int = (xml \\ "age").text.toInt
      val married: Boolean = (xml \\ "married").text.toBoolean
      Person(name, age, married)
    }

  // curl -X POST -H "Content-Type: application/vnd.acme.v1+xml" -d '<person><name>John Doe</name><age>25</age></person>' localhost:8080/person
  def personXmlV1EntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaVersionTypes.`application/vnd.acme.v1+xml`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val xml: Elem = XML.loadString(input)
      val name: String = (xml \\ "name").text
      val age: Int = (xml \\ "age").text.toInt
      Person(name, age, false)
    }

  // curl -X POST -H "Content-Type: application/vnd.acme.v2+xml" -d '<person><name>John Doe</name><age>25</age><married>true</married></person>' localhost:8080/person
  def personXmlV2EntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaVersionTypes.`application/vnd.acme.v2+xml`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val xml: Elem = XML.loadString(input)
      val name: String = (xml \\ "name").text
      val age: Int = (xml \\ "age").text.toInt
      val married: Boolean = (xml \\ "married").text.toBoolean
      Person(name, age, married)
    }

  // curl -X POST -H "Content-Type: application/json" -d '{"age": 25, "married": false, "name": "John Doe"}' localhost:8080/person
  def personJsonEntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaTypes.`application/json`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val tmp = input.parseJson.convertTo[PersonV2]
      Person(tmp.name, tmp.age, tmp.married)
    }

  // curl -X POST -H "Content-Type: application/vnd.acme.v1+json" -d '{"age": 25, "name": "John Doe"}' localhost:8080/person
  def personJsonV1EntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaVersionTypes.`application/vnd.acme.v1+json`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val tmp = input.parseJson.convertTo[PersonV1]
      Person(tmp.name, tmp.age, false)
    }

  // curl -X POST -H "Content-Type: application/vnd.acme.v2+json" -d '{"age": 25, "married": false, "name": "John Doe"}' localhost:8080/person
  def personJsonV2EntityUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(MediaVersionTypes.`application/vnd.acme.v2+json`).mapWithCharset { (data, charset) =>
      val input: String = if (charset == HttpCharsets.`UTF-8`) data.utf8String else data.decodeString(charset.nioCharset.name)
      val tmp = input.parseJson.convertTo[PersonV2]
      Person(tmp.name, tmp.age, tmp.married)
    }

  // will be used by the unmarshallers above
  implicit def personUnmarshaller(implicit mat: Materializer): FromEntityUnmarshaller[Person] =
    Unmarshaller.firstOf[HttpEntity, Person](
      personXmlEntityUnmarshaller, personXmlV1EntityUnmarshaller, personXmlV2EntityUnmarshaller,
      personJsonEntityUnmarshaller, personJsonV1EntityUnmarshaller, personJsonV2EntityUnmarshaller
    )
}
