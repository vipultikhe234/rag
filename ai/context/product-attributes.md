# Product attributes

**Status:** backend written, compiles; untested; no UI; no seed. API details: `ai/API_CONTRACTS.md`.

## Files (`backend/src/main/java/com/productrag/catalog/attribute/`)
- `ProductAttribute` (options as JSONB `List<String>`), `AttributeDataType`, `ProductAttributeRepository`,
  `ProductAttributeSpecifications`, `ProductAttributeService`, `ProductAttributeController`
  (`/api/v1/product-attributes`, no DELETE), `AttributeValueValidator`, `dto/ProductAttributeRequest|Response`.

## Rules
- Belongs to one category; applies to that category and all subcategories (`/applicable`, root first).
- Name unique within the category; slug unique across the category's ancestors and descendants
  (product values are keyed by slug), max 64.
- SELECT: 1-50 non-blank, case-insensitively distinct options; other types: no options.
- New attributes cannot be attached to an inactive category.
- Value validation (`AttributeValueValidator`, used by products and variants):
  STRING text; NUMBER number or numeric string (converted); BOOLEAN bool or "true"/"false"; SELECT must match an
  option (canonical casing stored). Required active attributes enforced for products (not variants).
  Inactive attributes: existing value kept, changing/adding it rejected. Undefined keys allowed (free-form).
- Errors: `VALIDATION_FAILED` with fields `attributes.<slug>`.
- Filterable attributes drive product list filters `attr=slug:value` (JSONB text equality, case-insensitive).

## Seed plan (30; category assignment)
- Electronics: RAM (NUMBER GB), Storage Capacity (NUMBER GB), Screen Size (NUMBER inch), Operating System
  (SELECT), Warranty Period (NUMBER months)
- Laptops: Processor (STRING, required), Weight (NUMBER kg), Refresh Rate (NUMBER Hz), Display Resolution (SELECT)
- Mobile Phones: Battery Capacity (NUMBER mAh, required), Camera Resolution (NUMBER MP), Network Support (SELECT, required)
- Fashion: Color (STRING), Gender (SELECT) · Footwear: Material (STRING), Shoe Size (SELECT, required)
- Men's Clothing: Clothing Size (SELECT, required), Fit Type (SELECT) · Women's Clothing: Fabric Type, Pattern
- Furniture: Dimensions (STRING cm) · Home Appliances: Power Consumption (NUMBER W), Wi-Fi Support (BOOLEAN),
  Energy Rating (SELECT) · Kitchen Appliances: Appliance Capacity (NUMBER L) · Watches: Water Resistance (SELECT)
- Computer Accessories: Bluetooth Support (BOOLEAN), USB Type (SELECT) · Toys and Games: Age Group (SELECT)
- Grocery: Country of Origin (STRING)
- Types/units above are the planned choice; confirm against the original prompt if it differs.

## Tests
- None yet.

## Known issues
- Changing an attribute's type/options does not migrate existing product values; such products fail validation
  on their next edit until fixed.
