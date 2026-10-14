# Product attributes

**Status:** done — backend, UI, tests, seeded. Many-to-many categories (V4) browser-verified on scratch. API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/attribute/`: `ProductAttribute` (options as JSONB `List<String>`), `AttributeDataType`,
  `ProductAttributeRepository`, `ProductAttributeSpecifications`, `ProductAttributeService`,
  `ProductAttributeController` (`/api/v1/product-attributes`, no DELETE), `AttributeValueValidator`,
  `dto/ProductAttributeRequest|Response`.
- Frontend: `pages/catalog/Attribute{List,Details,Form}Page.tsx`; routes `/product-attributes[/new|/:id|/:id/edit]`
  (`/new?categoryId=` preselects). Typed value inputs: `features/catalog/SpecFields.tsx`,
  `attributeValues.ts` (parse/collect/redistribute), `useSpecDefinitions.ts` (loads `/applicable` with inactive,
  moves values between typed fields and free-form rows when the category changes; blocks submit until loaded).

## Rules
- Assigned to 1-20 categories (`product_attribute_categories`, V4); applies to those categories and all their
  subcategories (`/applicable`, root first). Legacy `categoryId` in the body is still accepted.
- Name (case-insensitive) and slug are globally unique (product values are keyed by slug), slug max 64.
  Name clash -> 409 `ATTRIBUTE_ALREADY_EXISTS` (UI: Name field), slug clash -> 409 `SLUG_ALREADY_EXISTS` (Slug field).
- SELECT: 1-50 non-blank, case-insensitively distinct options; other types: no options.
- Newly assigned categories must be assignable (active, no inactive ancestor); existing links stay.
- List filters: `q`, `categoryId`, `dataType`, `status`, `required`, `filterable`.
- An option on a variant product may link to a SELECT or NUMBER attribute: SELECT restricts values to the
  attribute's options (canonical casing), NUMBER appends the unit to variant labels.
- Value validation (`AttributeValueValidator`, used by products and variants): STRING text; NUMBER number or
  numeric string (converted); BOOLEAN bool or "true"/"false"; SELECT must match an option (canonical casing
  stored). Required active attributes enforced for products (not variants). Inactive attributes: existing value
  kept, changing/adding it rejected (UI shows it read-only). Undefined keys allowed (free-form).
- Errors: `VALIDATION_FAILED` with fields `attributes.<slug>` (UI maps them to the typed field).
- Filterable attributes drive product list filters `attr=slug:value` (JSONB text equality, case-insensitive).
  API accepts at most 10 `attr` params; `pages/ProductListPage.tsx` lists the selected category's own attributes
  before inherited ones and refuses an 11th active filter with a toast.

## Seed data (30, one category each; * = inactive, R = required, F = filterable)
- Electronics: RAM (NUMBER GB, F), Storage Capacity (NUMBER GB, F), Processor (SELECT, F), Screen Size (NUMBER
  inches, F), Operating System (SELECT, F), Warranty Period (NUMBER months), Bluetooth Support (BOOLEAN, F),
  Wi-Fi Support (BOOLEAN, F), USB Type (SELECT, F), Refresh Rate (NUMBER Hz, F), Display Resolution (SELECT, F)
- Mobile Phones: Battery Capacity (NUMBER mAh), Camera Resolution (NUMBER MP), Network Support (SELECT 4G/5G, R F)
- Laptops: Weight (NUMBER kg)
- Home Appliances: Dimensions (STRING), Power Consumption (NUMBER watts), Energy Rating (SELECT, F)
- Kitchen Appliances: Appliance Capacity (NUMBER litres, F)
- Fashion: Color, Material, Clothing Size, Gender, Fit Type (SELECT, F), Fabric Type (SELECT), Pattern* (SELECT),
  Country of Origin* (SELECT)
- Footwear: Shoe Size (SELECT, R F) · Watches: Water Resistance (BOOLEAN, F) · Toys and Games: Age Group (SELECT, F)

## Tests
- `ProductAttributeServiceTest` (13), `AttributeValueValidatorTest` (5),
  `ProductManagementIntegrationTest.attributesAreGlobalAndCanApplyToSeveralCategories`, `CatalogFixturesTest` (fixture values
  validated through the real validator with inheritance); live API checks (typed errors, `attr` filter).

## Known issues
- Changing an attribute's type/options does not migrate existing product values; such products fail validation
  on their next edit until fixed (the edit form says so).
- Making an attribute required affects existing products only when they are next saved.
