package me.kevinlot.lite.formats

class ServerlessTest : FmtTestCase() {
    private val d = "$"

    private val service = """
        service: orders
        params:
          dev:
            domain: dev.example.test
          prod:
            domain: example.test
        custom:
          stage: $d{opt:stage, 'dev'}
          tables:
            orders: orders-$d{self:custom.stage}
          shared: $d{file(./config/shared.yml)}
          limits: $d{file(./config/shared.yml):limits}
        provider:
          name: aws
          environment:
            TABLE: $d{self:custom.tables.orders}
            REGION: $d{self:custom.shared.region}
            MAX: $d{self:custom.limits.max}
            TIMEOUT: $d{file(./config/shared.yml):limits.timeout}
            DOMAIN: $d{param:domain}
        functions:
          create-order:
            handler: src/create.handler
            environment:
              QUEUE_URL: !Ref OrdersQueue
              TABLE_ARN: !GetAtt OrdersTable.Arn
              TOPIC: !Sub "arn:aws:sns:$d{AWS::Region}:$d{AWS::AccountId}:$d{OrdersTopic.TopicName}"
        resources:
          - $d{file(resources/dynamodb.yml)}
          - Conditions:
              IsProd: !Equals [$d{self:custom.stage}, prod]
            Mappings:
              Sizes:
                dev:
                  memory: 128
            Resources:
              OrdersQueue:
                Type: AWS::SQS::Queue
                DependsOn: [OrdersTopic]
              OrdersTopic:
                Type: AWS::SNS::Topic
                Condition: IsProd
              Alarm:
                Type: AWS::CloudWatch::Alarm
                DependsOn:
                  - OrdersQueue
                Properties:
                  Threshold: !FindInMap [Sizes, dev, memory]
                  Name: !If [IsProd, a, b]
                  Function:
                    Fn::GetAtt: [CreateDashorderLambdaFunction, Arn]
                  Queue:
                    Ref: OrdersQueue
    """.trimIndent()

    private val shared = "region: eu-west-1\nlimits:\n  max: 10\n  timeout: 30\n"
    private val dynamodb = "Resources:\n  OrdersTable:\n    Type: AWS::DynamoDB::Table\n    Properties:\n      TableName: $d{self:custom.tables.orders}\n"

    private fun link(def: String, ref: String, defIn: String = "service", refIn: String = "service") {
        val paths = mapOf("service" to "serverless.yml", "shared" to "config/shared.yml", "dynamodb" to "resources/dynamodb.yml")
        val texts = mapOf("service" to service, "shared" to shared, "dynamodb" to dynamodb)
        val marked = texts.mapValues { (name, text) ->
            val withRef = text.takeIf { name != refIn } ?: text.replaceFirst(ref.replace("^", ""), ref.replace("^", "<ref>"))
            withRef.takeIf { name != defIn } ?: withRef.replaceFirst(def.replace("|", ""), def.replace("|", "<def>"))
        }
        check(*paths.map { (name, path) -> path to marked.getValue(name) }.toTypedArray())
    }

    // ---------------------------------------------------------------- Serverless variables

    fun `test self to a nested key`() = link("    |orders: orders-", "TABLE: $d{self:custom.tables.^orders}")
    fun `test each segment of self`() = link("  |tables:\n", "TABLE: $d{self:custom.^tables.orders}")
    fun `test first segment of self`() = link("|custom:\n", "TABLE: $d{self:^custom.tables.orders}")
    fun `test self through a file value`() = link("|region: eu-west-1", "$d{self:custom.shared.^region}", defIn = "shared")
    fun `test self through a file value with a key`() = link("  |max: 10", "$d{self:custom.limits.^max}", defIn = "shared")
    fun `test keys after a file`() = link("  |timeout: 30", "$d{file(./config/shared.yml):limits.^timeout}", defIn = "shared")
    /** One target per stage: the IDE offers the choice. */
    fun `test param leads to every stage that sets it`() {
        val file = myFixture.configureByText("serverless.yml", service)
        val word = com.intellij.psi.util.PsiTreeUtil.getParentOfType(file.findElementAt(service.indexOf("param:domain") + 7), FmtWord::class.java)!!
        val targets = word.references.filterIsInstance<com.intellij.psi.PsiPolyVariantReference>().flatMap { it.multiResolve(false).toList() }.mapNotNull { it.element }
        assertEquals(listOf("domain", "domain"), targets.map { it.text })
    }
    fun `test self from an included file reads the service`() = link("    |orders: orders-", "TableName: $d{self:custom.tables.^orders}", refIn = "dynamodb")

    fun `test file path`() = check("config/shared.yml" to shared, "serverless.yml" to service.replaceFirst("shared: $d{file(./config/shared.yml)}", "shared: $d{file(./config/<ref>shared.yml)}"))

    // ---------------------------------------------------------------- CloudFormation

    fun `test ref tag`() = link("      |OrdersQueue:\n        Type", "QUEUE_URL: !Ref ^OrdersQueue")
    fun `test ref key`() = link("      |OrdersQueue:\n        Type", "Ref: ^OrdersQueue")
    fun `test getatt to a resource of an included file`() = link("  |OrdersTable:", "!GetAtt ^OrdersTable.Arn", defIn = "dynamodb")
    fun `test sub`() = link("      |OrdersTopic:\n        Type", "$d{^OrdersTopic.TopicName}")
    fun `test depends on, flow list`() = link("      |OrdersTopic:\n        Type", "DependsOn: [^OrdersTopic]")
    fun `test depends on, block list`() = link("      |OrdersQueue:\n        Type", "          - ^OrdersQueue")
    fun `test condition`() = link("      |IsProd: !Equals", "Condition: ^IsProd")
    fun `test if`() = link("      |IsProd: !Equals", "!If [^IsProd, a, b]")
    fun `test find in map`() = link("      |Sizes:", "!FindInMap [^Sizes, dev, memory]")
    fun `test the lambda function of a serverless function`() = link("  |create-order:", "Fn::GetAtt: [^CreateDashorderLambdaFunction, Arn]")

    fun `test a plain cloudformation template`() = check(
        "template.yaml" to "Parameters:\n  <def>Env:\n    Type: String\nResources:\n  Bucket:\n    Type: AWS::S3::Bucket\n    Properties:\n      BucketName: !Sub \"data-$d{<ref>Env}\"\n",
    )

    fun `test pseudo parameters are no references`() {
        myFixture.configureByText("template.yaml", "Resources:\n  A:\n    Properties:\n      R: !Ref AWS::Region\n      S: !Sub \"$d{AWS::StackName}-x\"\n")
        assertEmpty(Fmt.links(myFixture.file).refs)
    }

    fun `test rename a custom key renames its uses`() {
        myFixture.configureByText("serverless.yml", "service: x\ncustom:\n  tab<caret>le: t\nprovider:\n  environment:\n    A: $d{self:custom.table}\n    B: \"$d{self:custom.table}-b\"\n")
        myFixture.renameElementAtCaret("tableName")
        myFixture.checkResult("service: x\ncustom:\n  tableName: t\nprovider:\n  environment:\n    A: $d{self:custom.tableName}\n    B: \"$d{self:custom.tableName}-b\"\n")
    }

    // ---------------------------------------------------------------- the layout of real services

    private val split = mapOf(
        "middleware/serverless.yml" to "service: seg\ncustom:\n  stage: dev\nprovider:\n  environment: $d{file(../deploy/env/env.$d{self:custom.stage}.yml)}\nfunctions:\n  - $d{file(../deploy/functions/worker.yml)}\n  - $d{file(../deploy/functions/api.yml)}\n",
        "deploy/env/env.dev.yml" to "LIMIT: 1\n",
        "deploy/env/env.prod.yml" to "LIMIT: 9\n",
        "deploy/functions/api.yml" to "api:\n  name: $d{self:service}-api\n",
    )

    private fun layout(vararg changed: Pair<String, String>) = check(*(split + changed).map { it.key to it.value }.toTypedArray())

    fun `test an included file reads the service of a folder beside it`() = layout(
        "middleware/serverless.yml" to split.getValue("middleware/serverless.yml").replace("  stage: dev", "  <def>stage: dev"),
        "deploy/functions/worker.yml" to "worker:\n  name: w-$d{self:custom.<ref>stage}\n",
    )

    fun `test a list of included files is indexed`() = layout(
        "deploy/functions/api.yml" to "api:\n  <def>name: x\n",
        "deploy/functions/worker.yml" to "worker:\n  tag: $d{self:functions.1.api.<ref>name}\n",
    )

    fun `test the log group of a function in an included file`() = layout(
        "deploy/functions/api.yml" to "<def>api:\n  name: x\n",
        "deploy/functions/worker.yml" to "worker:\n  log: !Ref <ref>ApiLogGroup\n",
    )

    fun `test a path with a variable stands for each file it may name`() {
        split.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        val text = "worker:\n  limit: $d{self:provider.environment.LIMIT}\n"
        val file = myFixture.addFileToProject("deploy/functions/worker.yml", text)
        val word = com.intellij.psi.util.PsiTreeUtil.getParentOfType(file.findElementAt(text.indexOf("LIMIT")), FmtWord::class.java)!!
        val targets = word.references.filterIsInstance<com.intellij.psi.PsiPolyVariantReference>().filter { it.rangeInElement.substring(word.text) == "LIMIT" }
            .flatMap { it.multiResolve(false).toList() }.mapNotNull { it.element?.containingFile?.name }
        assertEquals(listOf("env.dev.yml", "env.prod.yml"), targets)
    }

    /** LITE_FMT_CORPUS=<dir:dir>: the Serverless and CloudFormation references of YAML files, and those that lead nowhere. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_FMT_CORPUS")?.split(':')?.map { java.io.File(it) } ?: return
        dirs.forEach { com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val skipped = setOf("node_modules", ".git", ".github", "target", "build", "dist", ".serverless", ".terraform", ".terragrunt-cache", ".venv")
        val files = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in skipped }.filter { it.isFile && it.extension in setOf("yml", "yaml") } }
            .mapNotNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)?.let(psiManager::findFile) as? FmtFile }
        val linker = Yaml.linker!!
        val refs = files.flatMap { file -> Fmt.links(file).refs.filter { ref -> ref.keys.any { it.startsWith("sls:") || it.startsWith("cfn:") } }.map { file to it } }
        val missed = refs.filter { (file, ref) -> Serverless.resolve(file, ref, linker).isNullOrEmpty() }
        println("serverless corpus: ${files.size} YAML files, ${refs.size} references, ${refs.size - missed.size} resolved; by kind ${refs.groupingBy { it.second.keys.first().substringBeforeLast(':').take(12) }.eachCount()}")
        missed.groupingBy { (file, ref) -> "${ref.keys.first().take(70)} <- ${file.name}" }.eachCount().entries.sortedByDescending { it.value }.take(30).forEach { println("  ${it.value} x ${it.key}") }
    }
}
