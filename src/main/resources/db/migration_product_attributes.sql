-- ==============================================================================
-- TeknoyCart: Product Category Attributes & Multi-Variant Support
-- Migration: migration_product_attributes.sql
-- ==============================================================================
-- This migration adds category-specific attribute support to products.
-- Sellers can define attributes like Size, Uniform Type, Clothing Type (Jorts, T-Shirts, etc.),
-- Color, Flavor, Specs, etc. Stored as JSONB for high flexibility.
-- ==============================================================================

-- 1. Add category_attributes JSONB column to products table
ALTER TABLE public.products
ADD COLUMN IF NOT EXISTS category_attributes JSONB DEFAULT '[]'::jsonb;

-- 2. Add a GIN index on category_attributes for fast JSONB queries
CREATE INDEX IF NOT EXISTS idx_products_category_attributes
ON public.products USING GIN (category_attributes);

-- 3. Update the categories table with attribute templates column
ALTER TABLE public.categories
ADD COLUMN IF NOT EXISTS attribute_templates JSONB DEFAULT '[]'::jsonb;

-- 4. Populate attribute templates for existing categories
UPDATE public.categories SET attribute_templates = '[
  {"name": "Subject", "type": "text", "placeholder": "e.g. Calculus, Physics"},
  {"name": "Author", "type": "text", "placeholder": "e.g. James Stewart"},
  {"name": "Edition", "type": "text", "placeholder": "e.g. 9th Edition"},
  {"name": "ISBN", "type": "text", "placeholder": "e.g. 978-1-285-74062-1"}
]'::jsonb WHERE category_name = 'Books';

UPDATE public.categories SET attribute_templates = '[
  {"name": "Brand", "type": "text", "placeholder": "e.g. Staedtler, Faber-Castell"},
  {"name": "Set Size", "type": "text", "placeholder": "e.g. 12-piece, 24-piece"},
  {"name": "Type", "type": "select", "options": ["Pencil Set", "Drawing Board", "T-Square", "Compass Set", "Triangle Set", "Eraser Set", "Marker Set", "Complete Kit"], "placeholder": "Select type"}
]'::jsonb WHERE category_name = 'Drawing Tools';

-- Uniforms: Size and Uniform Type (no color)
UPDATE public.categories SET attribute_templates = '[
  {"name": "Size", "type": "select", "options": ["XS", "S", "M", "L", "XL", "XXL", "3XL", "26", "28", "30", "32", "34", "36"], "placeholder": "Select size", "is_multi_select": true},
  {"name": "Uniform Type", "type": "select", "options": ["PE Uniform", "School Uniform", "Department Shirt", "Lab Gown", "OJT Attire", "Event Shirt"], "placeholder": "Select type"},
  {"name": "Gender", "type": "select", "options": ["Unisex", "Male", "Female"], "placeholder": "Select fit"}
]'::jsonb WHERE category_name = 'Uniforms';

UPDATE public.categories SET attribute_templates = '[
  {"name": "Brand", "type": "text", "placeholder": "e.g. Casio, HP, Logitech"},
  {"name": "Model", "type": "text", "placeholder": "e.g. fx-991ES Plus"},
  {"name": "Specs", "type": "text", "placeholder": "e.g. 16GB RAM, 512GB SSD"},
  {"name": "Warranty", "type": "select", "options": ["No Warranty", "1 Month", "3 Months", "6 Months", "1 Year"], "placeholder": "Select warranty"},
  {"name": "Accessories Included", "type": "text", "placeholder": "e.g. Charger, Case, Cable"}
]'::jsonb WHERE category_name = 'Electronics';

UPDATE public.categories SET attribute_templates = '[
  {"name": "Type", "type": "text", "placeholder": "e.g. Miscellaneous, Gadgets"},
  {"name": "Quantity/Weight", "type": "text", "placeholder": "e.g. 500g, 6 pieces, 1 liter"}
]'::jsonb WHERE category_name = 'Others';

-- 5. Sync category_id sequence with current max ID so new inserts won't conflict with existing IDs
SELECT setval(
  pg_get_serial_sequence('public.categories', 'category_id'),
  COALESCE((SELECT MAX(category_id) FROM public.categories), 1)
);

-- 6. Insert new product categories (Clothes, Food & Beverages, School Supplies, Services)

-- Clothes category with options for T-Shirt, Shorts, Jorts, etc.
INSERT INTO public.categories (category_name, attribute_templates)
VALUES ('Clothes', '[
  {"name": "Clothing Type", "type": "select", "options": ["T-Shirt", "Shorts", "Jorts", "Pants / Jeans", "Hoodie / Jacket", "Polo / Collared Shirt", "Skirt / Dress", "Joggers / Sweatpants", "Tank Top / Sando", "Other"], "placeholder": "Select type"},
  {"name": "Size", "type": "select", "options": ["XS", "S", "M", "L", "XL", "XXL", "3XL", "28", "29", "30", "31", "32", "33", "34", "36", "38", "Free Size"], "placeholder": "Select size", "is_multi_select": true},
  {"name": "Color", "type": "select", "options": ["Black", "White", "Gray", "Denim Blue", "Navy Blue", "Maroon", "Beige / Khaki", "Brown", "Green", "Red", "Other"], "placeholder": "Select color", "is_multi_select": true},
  {"name": "Gender / Fit", "type": "select", "options": ["Unisex", "Men", "Women"], "placeholder": "Select fit"},
  {"name": "Brand", "type": "text", "placeholder": "e.g. Uniqlo, H&M, Cotton On, Thrifted"}
]'::jsonb)
ON CONFLICT (category_name) DO UPDATE SET
  attribute_templates = EXCLUDED.attribute_templates;

-- Food & Beverages
INSERT INTO public.categories (category_name, attribute_templates)
VALUES ('Food & Beverages', '[
  {"name": "Type", "type": "select", "options": ["Snack", "Beverage", "Meal Prep", "Baked Goods", "Homemade", "Instant Food", "Condiments"], "placeholder": "Select type"},
  {"name": "Flavor/Variant", "type": "text", "placeholder": "e.g. Chocolate, Vanilla, Spicy"},
  {"name": "Allergens", "type": "text", "placeholder": "e.g. Contains nuts, dairy-free"},
  {"name": "Expiry Date", "type": "text", "placeholder": "e.g. 2026-12-31"},
  {"name": "Serving Size", "type": "text", "placeholder": "e.g. 250ml, 6 pieces"}
]'::jsonb)
ON CONFLICT (category_name) DO UPDATE SET
  attribute_templates = EXCLUDED.attribute_templates;

-- School Supplies
INSERT INTO public.categories (category_name, attribute_templates)
VALUES ('School Supplies', '[
  {"name": "Type", "type": "select", "options": ["Notebook", "Folder", "Binder", "Index Cards", "Sticky Notes", "Paper", "Art Materials", "Lab Equipment", "Stationery Set"], "placeholder": "Select type"},
  {"name": "Brand", "type": "text", "placeholder": "e.g. Pilot, Muji, Cattleya"},
  {"name": "Size/Specification", "type": "text", "placeholder": "e.g. A4, Legal, 200 pages"},
  {"name": "Pack Quantity", "type": "text", "placeholder": "e.g. 3-pack, 12 pieces"}
]'::jsonb)
ON CONFLICT (category_name) DO UPDATE SET
  attribute_templates = EXCLUDED.attribute_templates;

-- Services
INSERT INTO public.categories (category_name, attribute_templates)
VALUES ('Services', '[
  {"name": "Service Type", "type": "select", "options": ["Tutoring", "Printing", "Design", "Programming Help", "Thesis Binding", "Photo/Video", "Delivery", "Other"], "placeholder": "Select service type"},
  {"name": "Duration", "type": "text", "placeholder": "e.g. 1 hour, per session"},
  {"name": "Availability", "type": "text", "placeholder": "e.g. Mon-Fri 3PM-6PM"}
]'::jsonb)
ON CONFLICT (category_name) DO UPDATE SET
  attribute_templates = EXCLUDED.attribute_templates;

-- 7. Grant read access to public/anon
GRANT SELECT ON public.categories TO authenticated;
GRANT SELECT ON public.categories TO anon;
