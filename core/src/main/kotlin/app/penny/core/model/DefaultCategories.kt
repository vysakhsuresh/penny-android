package app.penny.core.model

/**
 * Universal default category tree (SPEC Appendix A). Names here are English fallbacks keyed by [DefaultCategory.key];
 * the app shows localised names from string resources by key, and regional packs may add more.
 */
data class DefaultCategory(val key: String, val kind: CategoryKind, val name: String, val parentKey: String? = null, val excluded: Boolean = false)

object DefaultCategories {
    private fun e(key: String, name: String, parent: String? = null, excluded: Boolean = false) =
        DefaultCategory(key, CategoryKind.EXPENSE, name, parent, excluded)
    private fun i(key: String, name: String) = DefaultCategory(key, CategoryKind.INCOME, name)

    const val UNCATEGORISED = "uncat"
    const val ADJUSTMENTS = "adj"
    const val TRANSFERS = "xfer"
    const val UNRECORDED_CASH = "unrec"

    val tree: List<DefaultCategory> = listOf(
        i("inc.salary", "Salary"), i("inc.business", "Business"), i("inc.freelance", "Freelance"),
        i("inc.gift", "Gifts received"), i("inc.interest", "Interest and dividends"), i("inc.refund", "Refunds"),
        i("inc.remit", "Remittance received"), i("inc.other", "Other income"),
        e("food", "Food and groceries"), e("food.groc", "Groceries", "food"), e("food.market", "Markets", "food"),
        e("food.deliv", "Delivery", "food"),
        e("eat", "Eating out"), e("eat.rest", "Restaurants", "eat"), e("eat.cafe", "Cafés", "eat"), e("eat.take", "Takeaway", "eat"),
        e("home", "Housing"), e("home.rent", "Rent", "home"), e("home.mort", "Mortgage", "home"),
        e("home.maint", "Maintenance", "home"), e("home.hoa", "Society/HOA", "home"),
        e("util", "Utilities"), e("util.elec", "Electricity", "util"), e("util.water", "Water", "util"), e("util.gas", "Gas", "util"),
        e("util.net", "Internet", "util"), e("util.mobile", "Mobile", "util"),
        e("trans", "Transport"), e("trans.public", "Public transit", "trans"), e("trans.ride", "Ride-hailing", "trans"),
        e("trans.fuel", "Fuel", "trans"), e("trans.park", "Parking", "trans"), e("trans.toll", "Tolls", "trans"),
        e("trans.service", "Vehicle service", "trans"),
        e("health", "Health"), e("health.doc", "Doctor", "health"), e("health.pharm", "Pharmacy", "health"),
        e("health.ins", "Insurance", "health"), e("health.fit", "Fitness", "health"),
        e("fam", "Family"), e("fam.child", "Childcare", "fam"), e("fam.school", "School", "fam"), e("fam.allow", "Allowance", "fam"),
        e("fam.elder", "Elder care", "fam"), e("fam.remit", "Remittance sent", "fam"),
        e("shop", "Shopping"), e("shop.cloth", "Clothing", "shop"), e("shop.elec", "Electronics", "shop"),
        e("shop.home", "Home", "shop"), e("shop.care", "Personal care", "shop"),
        e("fun", "Entertainment"), e("fun.stream", "Streaming", "fun"), e("fun.events", "Events", "fun"),
        e("fun.games", "Games", "fun"), e("fun.hobby", "Hobbies", "fun"),
        e("travel", "Travel"), e("travel.flight", "Flights", "travel"), e("travel.hotel", "Hotels", "travel"),
        e("travel.local", "Local", "travel"),
        e("edu", "Education"), e("pets", "Pets"),
        e("gift", "Gifts and donations"), e("gift.gifts", "Gifts", "gift"), e("gift.relig", "Religious", "gift"),
        e("gift.charity", "Charity", "gift"),
        e("fin", "Financial"), e("fin.fees", "Fees", "fin"), e("fin.interest", "Interest", "fin"), e("fin.tax", "Taxes", "fin"),
        e("fin.ins", "Insurance", "fin"),
        e("loan", "Loans"), e("loan.given", "Given", "loan", excluded = true), e("loan.back", "Repaid to me", "loan", excluded = true),
        e("loan.emi", "EMI", "loan"), e("loan.repay", "Repayment", "loan"),
        e("save", "Savings and investments", excluded = true),
        e(TRANSFERS, "Transfers", excluded = true),
        e(ADJUSTMENTS, "Adjustments", excluded = true),
        e(UNRECORDED_CASH, "Unrecorded cash"),
        e(UNCATEGORISED, "Uncategorised"),
    )
}
