package me.kevinlot.lite.formats

import me.kevinlot.lite.formats.build.Fish
import me.kevinlot.lite.formats.build.Just
import me.kevinlot.lite.formats.build.Make
import me.kevinlot.lite.formats.config.Codeowners
import me.kevinlot.lite.formats.config.Env
import me.kevinlot.lite.formats.config.Hocon
import me.kevinlot.lite.formats.config.Ignore
import me.kevinlot.lite.formats.config.Ini
import me.kevinlot.lite.formats.config.Arb
import me.kevinlot.lite.formats.config.Json5
import me.kevinlot.lite.formats.config.Kdl
import me.kevinlot.lite.formats.config.Properties
import me.kevinlot.lite.formats.config.Toml
import me.kevinlot.lite.formats.config.Yaml
import me.kevinlot.lite.formats.data.Csv
import me.kevinlot.lite.formats.data.Log
import me.kevinlot.lite.formats.data.Sql
import me.kevinlot.lite.formats.diagram.D2
import me.kevinlot.lite.formats.diagram.Mermaid
import me.kevinlot.lite.formats.infra.Dockerfile
import me.kevinlot.lite.formats.infra.Hcl
import me.kevinlot.lite.formats.spec.Bruno
import me.kevinlot.lite.formats.spec.Gherkin
import me.kevinlot.lite.formats.spec.JFlex
import me.kevinlot.lite.formats.spec.Rego
import me.kevinlot.lite.formats.template.Css
import me.kevinlot.lite.formats.template.Handlebars
import me.kevinlot.lite.formats.template.Jinja
import me.kevinlot.lite.formats.template.Mustache
import me.kevinlot.lite.formats.template.Sass
import me.kevinlot.lite.formats.template.Scss

/* One line per extension point and format (the platform instantiates them by class name). */

object TomlLanguage : FmtLanguage("Toml", "TOML")
object TomlFileType : FmtFileType(Toml)
class TomlParserDefinition : FmtParserDefinition(Toml)
class TomlHighlighterFactory : FmtHighlighterFactory(Toml)
class TomlCommenter : FmtCommenter(Toml)
class TomlBraceMatcher : FmtBraceMatcher(Toml)
class TomlFindUsagesProvider : FmtFindUsagesProvider(Toml)

object YamlLanguage : FmtLanguage("Yaml", "YAML")
object YamlFileType : FmtFileType(Yaml)
class YamlParserDefinition : FmtParserDefinition(Yaml)
class YamlHighlighterFactory : FmtHighlighterFactory(Yaml)
class YamlCommenter : FmtCommenter(Yaml)
class YamlBraceMatcher : FmtBraceMatcher(Yaml)
class YamlFindUsagesProvider : FmtFindUsagesProvider(Yaml)

object KdlLanguage : FmtLanguage("Kdl", "KDL")
object KdlFileType : FmtFileType(Kdl)
class KdlParserDefinition : FmtParserDefinition(Kdl)
class KdlHighlighterFactory : FmtHighlighterFactory(Kdl)
class KdlCommenter : FmtCommenter(Kdl)
class KdlBraceMatcher : FmtBraceMatcher(Kdl)

object IniLanguage : FmtLanguage("Ini", "INI")
object IniFileType : FmtFileType(Ini)
class IniParserDefinition : FmtParserDefinition(Ini)
class IniHighlighterFactory : FmtHighlighterFactory(Ini)
class IniCommenter : FmtCommenter(Ini)
class IniBraceMatcher : FmtBraceMatcher(Ini)

object EnvLanguage : FmtLanguage("Env", "Dotenv")
object EnvFileType : FmtFileType(Env)
class EnvParserDefinition : FmtParserDefinition(Env)
class EnvHighlighterFactory : FmtHighlighterFactory(Env)
class EnvCommenter : FmtCommenter(Env)
class EnvBraceMatcher : FmtBraceMatcher(Env)
class EnvFindUsagesProvider : FmtFindUsagesProvider(Env)

object ArbLanguage : FmtLanguage("Arb", "ARB")
object ArbFileType : FmtFileType(Arb)
class ArbParserDefinition : FmtParserDefinition(Arb)
class ArbHighlighterFactory : FmtHighlighterFactory(Arb)
class ArbBraceMatcher : FmtBraceMatcher(Arb)
class ArbFindUsagesProvider : FmtFindUsagesProvider(Arb)

object Json5Language : FmtLanguage("Json5", "JSON5")
object Json5FileType : FmtFileType(Json5)
class Json5ParserDefinition : FmtParserDefinition(Json5)
class Json5HighlighterFactory : FmtHighlighterFactory(Json5)
class Json5Commenter : FmtCommenter(Json5)
class Json5BraceMatcher : FmtBraceMatcher(Json5)

object CsvLanguage : FmtLanguage("Csv", "CSV")
object CsvFileType : FmtFileType(Csv)
class CsvParserDefinition : FmtParserDefinition(Csv)
class CsvHighlighterFactory : FmtHighlighterFactory(Csv)
class CsvCommenter : FmtCommenter(Csv)
class CsvBraceMatcher : FmtBraceMatcher(Csv)

object LogLanguage : FmtLanguage("Log", "Log")
object LogFileType : FmtFileType(Log)
class LogParserDefinition : FmtParserDefinition(Log)
class LogHighlighterFactory : FmtHighlighterFactory(Log)

object SqlLanguage : FmtLanguage("Sql", "SQL")
object SqlFileType : FmtFileType(Sql)
class SqlParserDefinition : FmtParserDefinition(Sql)
class SqlHighlighterFactory : FmtHighlighterFactory(Sql)
class SqlCommenter : FmtCommenter(Sql)
class SqlBraceMatcher : FmtBraceMatcher(Sql)

object HoconLanguage : FmtLanguage("Hocon", "HOCON")
object HoconFileType : FmtFileType(Hocon)
class HoconParserDefinition : FmtParserDefinition(Hocon)
class HoconHighlighterFactory : FmtHighlighterFactory(Hocon)
class HoconCommenter : FmtCommenter(Hocon)
class HoconBraceMatcher : FmtBraceMatcher(Hocon)
class HoconFindUsagesProvider : FmtFindUsagesProvider(Hocon)

object DockerfileLanguage : FmtLanguage("Dockerfile", "Dockerfile")
object DockerfileFileType : FmtFileType(Dockerfile)
class DockerfileParserDefinition : FmtParserDefinition(Dockerfile)
class DockerfileHighlighterFactory : FmtHighlighterFactory(Dockerfile)
class DockerfileCommenter : FmtCommenter(Dockerfile)
class DockerfileBraceMatcher : FmtBraceMatcher(Dockerfile)
class DockerfileFindUsagesProvider : FmtFindUsagesProvider(Dockerfile)

object CssLanguage : FmtLanguage("Css", "CSS")
object CssFileType : FmtFileType(Css)
class CssParserDefinition : FmtParserDefinition(Css)
class CssHighlighterFactory : FmtHighlighterFactory(Css)
class CssCommenter : FmtCommenter(Css)
class CssBraceMatcher : FmtBraceMatcher(Css)
class CssFindUsagesProvider : FmtFindUsagesProvider(Css)

object ScssLanguage : FmtLanguage("Scss", "SCSS")
object ScssFileType : FmtFileType(Scss)
class ScssParserDefinition : FmtParserDefinition(Scss)
class ScssHighlighterFactory : FmtHighlighterFactory(Scss)
class ScssCommenter : FmtCommenter(Scss)
class ScssBraceMatcher : FmtBraceMatcher(Scss)
class ScssFindUsagesProvider : FmtFindUsagesProvider(Scss)

object IgnoreLanguage : FmtLanguage("Ignore", "Ignore")
object IgnoreFileType : FmtFileType(Ignore)
class IgnoreParserDefinition : FmtParserDefinition(Ignore)
class IgnoreHighlighterFactory : FmtHighlighterFactory(Ignore)
class IgnoreCommenter : FmtCommenter(Ignore)
class IgnoreBraceMatcher : FmtBraceMatcher(Ignore)
class IgnoreFindUsagesProvider : FmtFindUsagesProvider(Ignore)

object SassLanguage : FmtLanguage("Sass", "Sass")
object SassFileType : FmtFileType(Sass)
class SassParserDefinition : FmtParserDefinition(Sass)
class SassHighlighterFactory : FmtHighlighterFactory(Sass)
class SassCommenter : FmtCommenter(Sass)
class SassBraceMatcher : FmtBraceMatcher(Sass)
class SassFindUsagesProvider : FmtFindUsagesProvider(Sass)

object MakeLanguage : FmtLanguage("Make", "Makefile")
object MakeFileType : FmtFileType(Make)
class MakeParserDefinition : FmtParserDefinition(Make)
class MakeHighlighterFactory : FmtHighlighterFactory(Make)
class MakeCommenter : FmtCommenter(Make)
class MakeBraceMatcher : FmtBraceMatcher(Make)
class MakeFindUsagesProvider : FmtFindUsagesProvider(Make)

object JustLanguage : FmtLanguage("Just", "Just")
object JustFileType : FmtFileType(Just)
class JustParserDefinition : FmtParserDefinition(Just)
class JustHighlighterFactory : FmtHighlighterFactory(Just)
class JustCommenter : FmtCommenter(Just)
class JustBraceMatcher : FmtBraceMatcher(Just)
class JustFindUsagesProvider : FmtFindUsagesProvider(Just)

object HclLanguage : FmtLanguage("Hcl", "HCL")
object HclFileType : FmtFileType(Hcl)
class HclParserDefinition : FmtParserDefinition(Hcl)
class HclHighlighterFactory : FmtHighlighterFactory(Hcl)
class HclCommenter : FmtCommenter(Hcl)
class HclBraceMatcher : FmtBraceMatcher(Hcl)
class HclFindUsagesProvider : FmtFindUsagesProvider(Hcl)

object FishLanguage : FmtLanguage("Fish", "fish")
object FishFileType : FmtFileType(Fish)
class FishParserDefinition : FmtParserDefinition(Fish)
class FishHighlighterFactory : FmtHighlighterFactory(Fish)
class FishCommenter : FmtCommenter(Fish)
class FishBraceMatcher : FmtBraceMatcher(Fish)
class FishFindUsagesProvider : FmtFindUsagesProvider(Fish)

object BrunoLanguage : FmtLanguage("Bruno", "Bruno")
object BrunoFileType : FmtFileType(Bruno)
class BrunoParserDefinition : FmtParserDefinition(Bruno)
class BrunoHighlighterFactory : FmtHighlighterFactory(Bruno)
class BrunoCommenter : FmtCommenter(Bruno)
class BrunoBraceMatcher : FmtBraceMatcher(Bruno)
class BrunoFindUsagesProvider : FmtFindUsagesProvider(Bruno)

object CodeownersLanguage : FmtLanguage("Codeowners", "CODEOWNERS")
object CodeownersFileType : FmtFileType(Codeowners)
class CodeownersParserDefinition : FmtParserDefinition(Codeowners)
class CodeownersHighlighterFactory : FmtHighlighterFactory(Codeowners)
class CodeownersCommenter : FmtCommenter(Codeowners)
class CodeownersBraceMatcher : FmtBraceMatcher(Codeowners)
class CodeownersFindUsagesProvider : FmtFindUsagesProvider(Codeowners)

object D2Language : FmtLanguage("D2", "D2")
object D2FileType : FmtFileType(D2)
class D2ParserDefinition : FmtParserDefinition(D2)
class D2HighlighterFactory : FmtHighlighterFactory(D2)
class D2Commenter : FmtCommenter(D2)
class D2BraceMatcher : FmtBraceMatcher(D2)
class D2FindUsagesProvider : FmtFindUsagesProvider(D2)

object GherkinLanguage : FmtLanguage("Gherkin", "Gherkin")
object GherkinFileType : FmtFileType(Gherkin)
class GherkinParserDefinition : FmtParserDefinition(Gherkin)
class GherkinHighlighterFactory : FmtHighlighterFactory(Gherkin)
class GherkinCommenter : FmtCommenter(Gherkin)
class GherkinBraceMatcher : FmtBraceMatcher(Gherkin)
class GherkinFindUsagesProvider : FmtFindUsagesProvider(Gherkin)

object HandlebarsLanguage : FmtLanguage("Handlebars", "Handlebars")
object HandlebarsFileType : FmtFileType(Handlebars)
class HandlebarsParserDefinition : FmtParserDefinition(Handlebars)
class HandlebarsHighlighterFactory : FmtHighlighterFactory(Handlebars)
class HandlebarsCommenter : FmtCommenter(Handlebars)
class HandlebarsBraceMatcher : FmtBraceMatcher(Handlebars)
class HandlebarsFindUsagesProvider : FmtFindUsagesProvider(Handlebars)

object JFlexLanguage : FmtLanguage("JFlex", "JFlex")
object JFlexFileType : FmtFileType(JFlex)
class JFlexParserDefinition : FmtParserDefinition(JFlex)
class JFlexHighlighterFactory : FmtHighlighterFactory(JFlex)
class JFlexCommenter : FmtCommenter(JFlex)
class JFlexBraceMatcher : FmtBraceMatcher(JFlex)
class JFlexFindUsagesProvider : FmtFindUsagesProvider(JFlex)

object JinjaLanguage : FmtLanguage("Jinja", "Jinja2")
object JinjaFileType : FmtFileType(Jinja)
class JinjaParserDefinition : FmtParserDefinition(Jinja)
class JinjaCommenter : FmtCommenter(Jinja)
class JinjaBraceMatcher : FmtBraceMatcher(Jinja)
class JinjaFindUsagesProvider : FmtFindUsagesProvider(Jinja)

object MermaidLanguage : FmtLanguage("Mermaid", "Mermaid")
object MermaidFileType : FmtFileType(Mermaid)
class MermaidParserDefinition : FmtParserDefinition(Mermaid)
class MermaidHighlighterFactory : FmtHighlighterFactory(Mermaid)
class MermaidCommenter : FmtCommenter(Mermaid)
class MermaidBraceMatcher : FmtBraceMatcher(Mermaid)
class MermaidFindUsagesProvider : FmtFindUsagesProvider(Mermaid)

object MustacheLanguage : FmtLanguage("Mustache", "Mustache")
object MustacheFileType : FmtFileType(Mustache)
class MustacheParserDefinition : FmtParserDefinition(Mustache)
class MustacheHighlighterFactory : FmtHighlighterFactory(Mustache)
class MustacheCommenter : FmtCommenter(Mustache)
class MustacheBraceMatcher : FmtBraceMatcher(Mustache)
class MustacheFindUsagesProvider : FmtFindUsagesProvider(Mustache)

object PropertiesLanguage : FmtLanguage("Properties", "Properties")
object PropertiesFileType : FmtFileType(Properties)
class PropertiesParserDefinition : FmtParserDefinition(Properties)
class PropertiesHighlighterFactory : FmtHighlighterFactory(Properties)
class PropertiesCommenter : FmtCommenter(Properties)
class PropertiesBraceMatcher : FmtBraceMatcher(Properties)
class PropertiesFindUsagesProvider : FmtFindUsagesProvider(Properties)

object RegoLanguage : FmtLanguage("Rego", "Rego")
object RegoFileType : FmtFileType(Rego)
class RegoParserDefinition : FmtParserDefinition(Rego)
class RegoHighlighterFactory : FmtHighlighterFactory(Rego)
class RegoCommenter : FmtCommenter(Rego)
class RegoBraceMatcher : FmtBraceMatcher(Rego)
class RegoFindUsagesProvider : FmtFindUsagesProvider(Rego)

val ALL_FILE_TYPES: List<FmtFileType> by lazy {
    listOf(
        TomlFileType, YamlFileType, KdlFileType, IniFileType, EnvFileType, Json5FileType, CsvFileType, SqlFileType, HoconFileType, DockerfileFileType,
        CssFileType, ScssFileType, SassFileType, IgnoreFileType, MakeFileType, JustFileType, HclFileType, FishFileType, BrunoFileType, CodeownersFileType,
        D2FileType, GherkinFileType, HandlebarsFileType, JFlexFileType, JinjaFileType, MermaidFileType, MustacheFileType, PropertiesFileType, RegoFileType,
        ArbFileType, LogFileType,
    )
}
val FMT_LANGUAGES: Map<String, FmtLanguage> by lazy {
    listOf(
        TomlLanguage, YamlLanguage, KdlLanguage, IniLanguage, EnvLanguage, Json5Language, CsvLanguage, SqlLanguage, HoconLanguage, DockerfileLanguage,
        CssLanguage, ScssLanguage, SassLanguage, IgnoreLanguage, MakeLanguage, JustLanguage, HclLanguage, FishLanguage, BrunoLanguage, CodeownersLanguage,
        D2Language, GherkinLanguage, HandlebarsLanguage, JFlexLanguage, JinjaLanguage, MermaidLanguage, MustacheLanguage, PropertiesLanguage, RegoLanguage,
        ArbLanguage, LogLanguage,
    ).associateBy { it.id.removePrefix("Lite") }
}

/** The file type of each format (by format), for lookups by type. */
val FMT_FILE_TYPES: Map<Format, FmtFileType> by lazy { ALL_FILE_TYPES.associateBy { it.format } }
