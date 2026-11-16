package app.penny.domain

import app.penny.domain.ops.BUDGET_TEMPLATES
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual

/** BU-09: a template never assigns more than the income it is given (it showed "available to assign" below zero). */
class BudgetTemplatesTest : FunSpec({
    BUDGET_TEMPLATES.forEach { (key, shares) ->
        test("$key assigns at most 100% of income") { shares.sumOf { it.second } shouldBeLessThanOrEqual 10_000 }
    }
})
