#!/usr/bin/env -S scala-cli shebang
// SPDX-License-Identifier: Apache-2.0
//> using scala "2.13"
//> using dep "io.circe::circe-yaml:0.14.2"
//> using dep "com.lihaoyi::os-lib:0.9.0"

/* Generates a Mergify config YAML (to STDOUT) based on input config
 *
 * There are built-in conditions, but different CI requires different conditions
 * Listed branches should be stable branches that we want to backport to, in ascending order
 * {{{
 * conditions:
     - status-success=Travis CI - Pull Request
   branches:
     - 1.2.x
     - 1.3.x
     - 1.4.x
 * }}}
 */

import io.circe._
import io.circe.syntax._ // for .asJson
import io.circe.yaml.parser
import io.circe.yaml.syntax._ // for .asYaml

def mergeQueue(conditions: List[String]) = Json.obj(
  "name" -> "default".asJson,
  "conditions" -> conditions.asJson
)

val queueAction = Json.obj(
  "queue" -> Json.obj(
    "name" -> "default".asJson,
    "method" -> "squash".asJson,
    "update_method" -> "merge".asJson
  )
)

def mergeToMain(conditions: List[String]) = Json.obj(
  "name" -> "automatic squash-and-merge on CI success and review".asJson,
  "conditions" -> (conditions ++ List(
    "#approved-reviews-by>=1",
    "#changes-requested-reviews-by=0",
    "base=main",
    "label=\"Please Merge\"",
    "label!=\"DO NOT MERGE\"",
    "label!=\"bp-conflict\""
  )).asJson,
  "actions" -> queueAction
)

def makeBackportRule(branches: List[String]): Json = {
  Json.obj(
    "name" -> s"""backport to ${branches.mkString(", ")}""".asJson,
    "conditions" -> List("merged", "base=main", s"milestone=${branches.head}").asJson,
    "actions" -> Json.obj(
      "backport" -> Json.obj(
        "branches" -> branches.asJson,
        "labels" -> List("Backport").asJson,
        "ignore_conflicts" -> true.asJson,
        "label_conflicts" -> "bp-conflict".asJson
      ),
      "label" -> Json.obj(
        "add" -> List("Backported").asJson
      )
    )
  )
}

def backportMergeRule(conditions: List[String])(branch: String): Json = Json.obj(
  "name" -> s"automatic squash-and-mege of $branch backport PRs".asJson,
  "conditions" -> (conditions ++ List(
    "#changes-requested-reviews-by=0",
    s"base=$branch",
    "label=\"Backport\"",
    "label!=\"DO NOT MERGE\"",
    "label!=\"bp-conflict\""
  )).asJson,
  "actions" -> queueAction
)


def error(msg: String) = throw new Exception(msg) with scala.util.control.NoStackTrace

def processTemplate(path: os.Path): (List[String], List[String]) = {
  val contents = os.read(path)
  val parsed = parser.parse(contents)
                     .getOrElse(error(s"Invalid YAML $path"))

  val cursor: HCursor = parsed.hcursor

  val conditions = cursor.downField("conditions")
                         .as[List[String]]
                         .getOrElse(error(s"Invalid template, expected field 'conditions': List[String]"))

  val branches = cursor.downField("branches")
                       .as[List[String]]
                       .getOrElse(error(s"Invalid template, expected field 'branches': List[String]"))
  (conditions, branches)
}

require(args.size == 1, "Usage: <config yaml>")
val template = os.Path(os.RelPath(args(0)), os.pwd)

val (conditions, branches) = processTemplate(template)

val branchSets = branches.scanRight(List.empty[String])(_ :: _).init.reverse

val config = Json.obj(
	"queue_rules" -> Json.fromValues(
		mergeQueue(conditions) ::
		Nil
	),
	"pull_request_rules" -> Json.fromValues(
		mergeToMain(conditions) ::
		branchSets.map(makeBackportRule) :::
		branches.map(backportMergeRule(conditions))
	)
)
println(config.asYaml.spaces2)
