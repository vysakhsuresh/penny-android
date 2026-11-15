## Requirements
<!-- IDs from docs/SPEC.md, e.g. TX-02, TX-03 -->

## CRUD matrix
<!-- Paste the rows you changed from docs/CRUD-MATRIX.md and their new status. -->

## Checks
- [ ] `./gradlew lint detekt test` green
- [ ] Empty, loading, error states present; destructive actions use 5 s undo
- [ ] Strings in resources (plurals where counts appear); RTL and 200% font checked
- [ ] No new permissions without a PRIVACY-MATRIX entry; no READ_SMS, ads, tracking SDKs or lending
- [ ] SPEC §2: none of the 40 audit gaps apply to touched screens
- [ ] DECISIONS.md updated for anything the spec did not cover
