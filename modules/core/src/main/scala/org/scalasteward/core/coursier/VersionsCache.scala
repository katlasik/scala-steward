/*
 * Copyright 2018-2020 Scala Steward contributors
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

package org.scalasteward.core.coursier

import cats.Parallel
import cats.implicits._
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, KeyEncoder}
import java.util.concurrent.TimeUnit
import org.scalasteward.core.coursier.VersionsCache.{Key, Value}
import org.scalasteward.core.data.{Dependency, Resolver, Scope, Version}
import org.scalasteward.core.persistence.KeyValueStore
import org.scalasteward.core.util.{DateTimeAlg, MonadThrowable}
import scala.concurrent.duration.FiniteDuration

final class VersionsCache[F[_]](
    cacheTtl: FiniteDuration,
    store: KeyValueStore[F, Key, Value]
)(
    implicit
    coursierAlg: CoursierAlg[F],
    dateTimeAlg: DateTimeAlg[F],
    parallel: Parallel[F],
    F: MonadThrowable[F]
) {
  def getVersions(dependency: Scope.Dependency, maxAge: Option[FiniteDuration]): F[List[Version]] =
    dependency.resolvers
      .parFlatTraverse(getVersionsImpl(dependency.value, _, maxAge.getOrElse(cacheTtl)))
      .map(_.sorted)

  private def getVersionsImpl(
      dependency: Dependency,
      resolver: Resolver,
      maxAge: FiniteDuration
  ): F[List[Version]] =
    dateTimeAlg.currentTimeMillis.flatMap { now =>
      val key = Key(dependency, resolver)
      store.get(key).flatMap {
        case Some(value) if value.age(now) <= (maxAge * value.maxAgeFactor) =>
          F.pure(value.versions)
        case maybeValue =>
          coursierAlg.getVersions(dependency, resolver).attempt.flatMap {
            case Right(versions) =>
              store.put(key, Value(now, versions, None)).as(versions)
            case Left(throwable) =>
              val versions = maybeValue.map(_.versions).getOrElse(List.empty)
              store.put(key, Value(now, versions, Some(throwable.getMessage))).as(versions)
          }
      }
    }
}

object VersionsCache {
  final case class Key(dependency: Dependency, resolver: Resolver) {
    override val toString: String =
      resolver.path + "/" +
        dependency.groupId.value.replace('.', '/') + "/" +
        dependency.artifactId.crossName +
        dependency.scalaVersion.fold("")("_" + _.value) +
        dependency.sbtVersion.fold("")("_" + _.value)
  }

  object Key {
    implicit val keyKeyEncoder: KeyEncoder[Key] =
      KeyEncoder.instance(_.toString)
  }

  final case class Value(
      updatedAt: Long,
      versions: List[Version],
      maybeError: Option[String]
  ) {
    def age(now: Long): FiniteDuration =
      FiniteDuration(now - updatedAt, TimeUnit.MILLISECONDS)

    def maxAgeFactor: Long =
      if (maybeError.nonEmpty && versions.isEmpty) 4L else 1L
  }

  object Value {
    implicit val valueCodec: Codec[Value] =
      deriveCodec
  }
}
