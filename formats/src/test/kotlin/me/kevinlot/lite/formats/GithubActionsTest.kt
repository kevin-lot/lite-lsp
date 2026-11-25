package me.kevinlot.lite.formats

class GithubActionsTest : FmtTestCase() {
    private val d = "$"

    private val workflow = """
        name: release
        on:
          workflow_call:
            inputs:
              environment:
                type: string
            secrets:
              token:
                required: true
            outputs:
              version:
                value: $d{{ jobs.build.outputs.version }}
          workflow_dispatch:
            inputs:
              dry-run:
                type: boolean
        env:
          REGION: eu-west-1
        jobs:
          build:
            runs-on: ubuntu-latest
            env:
              TARGET: lambda
            outputs:
              version: $d{{ steps.tag.outputs.value }}
            strategy:
              matrix:
                node: [20, 22]
            steps:
              - id: tag
                env:
                  LOCAL: 1
                run: |
                  echo "value=1.2.3" >> "${d}GITHUB_OUTPUT"
                  echo "$d{{ env.LOCAL }} $d{{ matrix.node }}"
              - name: show
                if: steps.tag.outputs.value != '' && inputs.dry-run
                run: echo "$d{{ env.TARGET }} $d{{ env.REGION }} $d{{ inputs.environment }} $d{{ secrets.token }}"
          deploy:
            needs: [build]
            if: needs.build.result == 'success'
            uses: ./.github/workflows/_deploy.yml
            with:
              version: $d{{ needs.build.outputs.version }}
            secrets:
              key: $d{{ secrets.token }}
          notify:
            needs:
              - build
              - deploy # last
            runs-on: ubuntu-latest
            steps:
              - uses: ./.github/actions/say
                with:
                  text: $d{{ needs.deploy.outputs.url }}
    """.trimIndent()

    private val deploy = """
        on:
          workflow_call:
            inputs:
              version:
                type: string
            secrets:
              key:
                required: true
            outputs:
              url:
                value: $d{{ jobs.run.outputs.url }}
        jobs:
          run:
            runs-on: ubuntu-latest
            outputs:
              url: x
            steps:
              - run: echo
    """.trimIndent()

    private val action = "inputs:\n  text:\n    required: true\nruns:\n  using: composite\n  steps:\n    - id: one\n      run: echo \"$d{{ inputs.text }}\"\n      shell: bash\n"

    private fun link(def: String, ref: String, defIn: String = "main") {
        val files = mapOf("main" to ".github/workflows/release.yml", "deploy" to ".github/workflows/_deploy.yml", "action" to ".github/actions/say/action.yml")
        val texts = mapOf("main" to workflow, "deploy" to deploy, "action" to action)
        val marked = texts.mapValues { (name, text) ->
            val withRef = text.takeIf { name != "main" } ?: text.replaceFirst(ref.replace("^", ""), ref.replace("^", "<ref>"))
            withRef.takeIf { name != defIn } ?: withRef.replaceFirst(def.replace("|", ""), def.replace("|", "<def>"))
        }
        check(*files.map { (name, path) -> path to marked.getValue(name) }.toTypedArray())
    }

    fun `test needs list to the job`() = link("  |build:\n    runs-on", "needs: [^build]")
    fun `test needs items to the job`() = link("  |deploy:\n    needs", "- ^deploy # last")
    fun `test needs in a condition`() = link("  |build:\n    runs-on", "if: needs.^build.result")
    fun `test job output`() = link("    outputs:\n      |version: $d{{ steps", "needs.build.outputs.^version }}")
    fun `test jobs context of a called workflow`() = link("    outputs:\n      |version: $d{{ steps", "jobs.build.outputs.^version")
    fun `test step id`() = link("- id: |tag", "$d{{ steps.^tag.outputs.value }}")
    fun `test step output written by the script`() = link("echo \"|value=1.2.3\"", "steps.tag.outputs.^value }}")
    fun `test step in a condition without braces`() = link("- id: |tag", "if: steps.^tag.outputs")
    fun `test workflow_call input`() = link("      |environment:", "inputs.^environment }}")
    fun `test workflow_dispatch input in a condition`() = link("      |dry-run:", "&& inputs.^dry-run")
    fun `test secret`() = link("      |token:", "$d{{ secrets.^token }}\"")
    fun `test step env wins inside its step`() = link("|LOCAL: 1", "env.^LOCAL }}")
    fun `test job env`() = link("|TARGET: lambda", "env.^TARGET }}")
    fun `test workflow env`() = link("|REGION: eu-west-1", "env.^REGION }}")
    fun `test matrix key`() = link("|node: [20, 22]", "matrix.^node }}")

    fun `test with key to the input of the called workflow`() = link("      |version:\n        type", "    with:\n      ^version:", defIn = "deploy")
    fun `test secrets key to the secret of the called workflow`() = link("      |key:\n        required", "    secrets:\n      ^key:", defIn = "deploy")
    fun `test output of a job that calls a workflow`() = link("      |url:\n        value", "needs.deploy.outputs.^url", defIn = "deploy")
    fun `test with key to the input of a local action`() = link("  |text:\n    required", "        with:\n          ^text:", defIn = "action")

    fun `test uses leads to the workflow file`() =
        check(".github/workflows/_deploy.yml" to deploy, ".github/workflows/release.yml" to workflow.replaceFirst("uses: ./.github/workflows/_deploy.yml", "uses: ./.github/<ref>workflows/_deploy.yml"))

    fun `test uses leads to the action file`() =
        check(".github/actions/say/action.yml" to action, ".github/workflows/release.yml" to workflow.replaceFirst("uses: ./.github/actions/say", "uses: ./.github/<ref>actions/say"))

    fun `test rename a job renames needs and expressions`() {
        myFixture.configureByText("ci.yml", "on: push\njobs:\n  bui<caret>ld:\n    runs-on: x\n  test:\n    needs: build\n    if: needs.build.result == 'success'\n    runs-on: $d{{ needs.build.outputs.os }}\n")
        myFixture.renameElementAtCaret("compile")
        myFixture.checkResult("on: push\njobs:\n  compile:\n    runs-on: x\n  test:\n    needs: compile\n    if: needs.compile.result == 'success'\n    runs-on: $d{{ needs.compile.outputs.os }}\n")
    }

    fun `test a yaml file that is not a workflow has no such links`() {
        myFixture.configureByText("values.yml", "steps:\n  - id: a\nmessage: $d{{ steps.a.outputs.x }}\n")
        assertEmpty(Fmt.links(myFixture.file).refs)
    }
}
