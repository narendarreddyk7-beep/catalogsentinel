#!/usr/bin/env python3
"""Builds tests/golden-matching/golden-listings.tsv — the hand-labeled product-matching evaluation set.

Every row is a listing as a supplier ("ref") or a marketplace seller ("mkt", "mkt2") might publish it.
Rows with the same truth_key are the same sellable product variant (positives). Rows in the same family
with different truth keys are hard negatives (other storage/color/size/generation/pack/tier).

Brand and product names are used nominatively to make titles realistic. GTINs are synthetic: they use the
GS1 "restricted circulation" prefix 200-299, which is never assigned to real trade items, plus a valid
check digit. Run: python3 tests/golden-matching/build_golden_set.py
"""
import hashlib
import pathlib

OUT = pathlib.Path(__file__).with_name("golden-listings.tsv")


def gtin13(key: str) -> str:
    body = "2" + str(int(hashlib.sha256(key.encode()).hexdigest(), 16))[:11]
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body))
    return body + str((10 - total % 10) % 10)


# (truth_key, source, title, brand, model, gtin, attributes, category_override)
# gtin: True -> the truth key's GTIN; None -> absent; "key:<other>" -> the GTIN of another truth key
FAMILIES = {
    "sony-wh1000x": ("Electronics > Audio > Headphones", [
        ("sony-wh1000xm5-black", "ref", "Sony WH-1000XM5 Wireless Noise Canceling Headphones, Black", "Sony", "WH-1000XM5", True, "color=Black", None),
        ("sony-wh1000xm5-black", "mkt", "SONY WH1000XM5 Wireless Industry Leading Noise Cancelling Over-Ear Headphones - Black", "", "WH1000XM5/B", None, "", "Headphones"),
        ("sony-wh1000xm5-black", "mkt2", "Sony WH-1000XM5 Noise Cancelling Wireles Headphones Black", "Sony Corporation", "", True, "Colour=Black", "Audio"),
        ("sony-wh1000xm5-silver", "ref", "Sony WH-1000XM5 Wireless Noise Canceling Headphones, Silver", "Sony", "WH-1000XM5", True, "color=Silver", None),
        ("sony-wh1000xm5-silver", "mkt", "Sony WH-1000XM5 Headphones (Silver)", "Sony", "WH1000XM5/S", None, "", "Headphones"),
        ("sony-wh1000xm4-black", "ref", "Sony WH-1000XM4 Wireless Noise Canceling Headphones, Black", "Sony", "WH-1000XM4", True, "color=Black", None),
        ("sony-wh1000xm4-black", "mkt", "Sony WH1000XM4 Noise Cancelling Headphones Black", "Sony", "", None, "", "Headphones"),
    ]),
    "iphone-15": ("Electronics > Phones > Smartphones", [
        ("iphone15pro-128-natural", "ref", "Apple iPhone 15 Pro 128GB Natural Titanium", "Apple", "", True, "storage=128 GB;color=Natural Titanium", None),
        ("iphone15pro-128-natural", "mkt", "iPhone 15 Pro, 128 GB, Natural Titanium - Unlocked", "Apple Inc.", "", None, "", "Cell Phones"),
        ("iphone15pro-256-natural", "ref", "Apple iPhone 15 Pro 256GB Natural Titanium", "Apple", "", True, "storage=256 GB;color=Natural Titanium", None),
        ("iphone15pro-256-natural", "mkt", "Apple iPhone 15 Pro (256 GB) - Natural Titanium", "Apple", "", True, "", "Cell Phones"),
        ("iphone15pro-128-blue", "ref", "Apple iPhone 15 Pro 128GB Blue Titanium", "Apple", "", True, "storage=128 GB;color=Blue Titanium", None),
        ("iphone15-128-black", "ref", "Apple iPhone 15 128GB Black", "Apple", "", True, "storage=128 GB;color=Black", None),
        ("iphone15-128-black", "mkt", "iPhone 15 128GB Black Unlocked Smartphone", "Apple", "", None, "", "Cell Phones"),
        ("iphone15promax-256-natural", "ref", "Apple iPhone 15 Pro Max 256GB Natural Titanium", "Apple", "", True, "storage=256 GB;color=Natural Titanium", None),
    ]),
    "samsung-q80c": ("Electronics > TV & Video > Televisions", [
        ("samsung-qn55q80c", "ref", "Samsung 55\" Class Q80C QLED 4K Smart TV (2023)", "Samsung", "QN55Q80CAFXZA", True, "screen size=55 in", None),
        ("samsung-qn55q80c", "mkt", "SAMSUNG 55-Inch Class QLED 4K Q80C Series Quantum HDR+ Smart TV", "Samsung Electronics", "QN55Q80C", None, "", "Televisions"),
        ("samsung-qn55q80c", "mkt2", "Samsung QN55Q80CAFXZA 55 inch QLED TV", "", "QN55Q80CAFXZA", True, "", "TVs"),
        ("samsung-qn65q80c", "ref", "Samsung 65\" Class Q80C QLED 4K Smart TV (2023)", "Samsung", "QN65Q80CAFXZA", True, "screen size=65 in", None),
        ("samsung-qn65q80c", "mkt", "SAMSUNG 65-Inch Class QLED 4K Q80C Series Smart TV", "Samsung", "QN65Q80C", None, "", "Televisions"),
    ]),
    "airpods": ("Electronics > Audio > Earbuds", [
        ("airpods-pro-2-usbc", "ref", "Apple AirPods Pro (2nd Generation) with MagSafe Case (USB-C)", "Apple", "MTJV3AM/A", True, "", None),
        ("airpods-pro-2-usbc", "mkt", "Apple AirPods Pro 2nd Gen Wireless Earbuds USB-C", "Apple", "MTJV3AM/A", None, "", "Earbuds"),
        ("airpods-pro-2-usbc", "mkt2", "AirPods Pro (2nd generation) USB C charging case", "", "MTJV3", None, "", "Headphones"),
        ("airpods-3", "ref", "Apple AirPods (3rd Generation) with Lightning Charging Case", "Apple", "MPNY3AM/A", True, "", None),
        ("airpods-3", "mkt", "Apple AirPods 3rd Gen Lightning Case", "Apple", "", None, "", "Earbuds"),
    ]),
    "kindle": ("Electronics > E-Readers", [
        ("kindle-pw-16", "ref", "Kindle Paperwhite (16 GB) - 6.8\" display, 11th generation, Black", "Amazon", "", True, "storage=16 GB;color=Black", None),
        ("kindle-pw-16", "mkt", "Amazon Kindle Paperwhite 16GB 6.8 inch 11th Gen Black", "Amazon", "", None, "", "eReaders"),
        ("kindle-pw-sig-32", "ref", "Kindle Paperwhite Signature Edition (32 GB) - 6.8\" display, 11th generation", "Amazon", "", True, "storage=32 GB", None),
        ("kindle-pw-sig-32", "mkt", "Kindle Paperwhite Signature Edition 32GB 11th Generation", "Amazon", "", None, "", "eReaders"),
    ]),
    "echo-dot": ("Electronics > Smart Home > Speakers", [
        ("echo-dot-5-charcoal", "ref", "Echo Dot (5th Gen, 2022 release) Smart speaker with Alexa, Charcoal", "Amazon", "", True, "color=Charcoal", None),
        ("echo-dot-5-charcoal", "mkt", "Amazon Echo Dot 5th Generation Smart Speaker - Charcoal", "Amazon", "", None, "", "Smart Speakers"),
        ("echo-dot-5-charcoal", "mkt2", "Echo Dot 5th Gen Alexa Speaker Charcoal Grey", "Amazon", "", True, "", "Speakers"),
        ("echo-dot-5-white", "ref", "Echo Dot (5th Gen, 2022 release) Smart speaker with Alexa, Glacier White", "Amazon", "", True, "color=Glacier White", None),
        ("echo-dot-4-charcoal", "ref", "Echo Dot (4th Gen, 2020 release) Smart speaker with Alexa, Charcoal", "Amazon", "", True, "color=Charcoal", None),
        ("echo-dot-4-charcoal", "mkt", "Amazon Echo Dot 4th Generation Charcoal", "Amazon", "", None, "", "Smart Speakers"),
    ]),
    "lego-icons": ("Toys > Building Sets", [
        ("lego-10318", "ref", "LEGO Icons Concorde 10318 Building Set for Adults", "LEGO", "10318", True, "", None),
        ("lego-10318", "mkt", "LEGO 10318 Icons Concorde Airplane Model Kit (2083 Pieces)", "The LEGO Group", "10318", None, "", "Toys"),
        ("lego-10318", "mkt2", "Lego Concorde Icons Set 10318", "", "", None, "", "Building Toys"),
        ("lego-10497", "ref", "LEGO Icons Galaxy Explorer 10497 Building Set", "LEGO", "10497", True, "", None),
        ("lego-10497", "mkt", "LEGO 10497 Galaxy Explorer Spaceship Model (1254 Pieces)", "LEGO", "10497", None, "", "Toys"),
    ]),
    "dewalt-drill": ("Tools > Power Tools > Drills", [
        ("dewalt-dcd771c2", "ref", "DEWALT 20V MAX Cordless Drill/Driver Kit, Compact, 1/2-Inch (DCD771C2)", "DEWALT", "DCD771C2", True, "", None),
        ("dewalt-dcd771c2", "mkt", "Dewalt DCD771C2 20V Max Compact Drill Driver Kit with 2 Batteries", "DeWalt", "DCD771C2", None, "", "Power Tools"),
        ("dewalt-dcd771c2", "mkt2", "DEWALT 20-Volt MAX Lithium-Ion Cordless 1/2 in. Drill/Driver Kit DCD771C2", "Dewalt Tools", "", None, "", "Drills"),
        ("dewalt-dcd777c2", "ref", "DEWALT 20V MAX Brushless Cordless Drill/Driver Kit, 1/2-Inch (DCD777C2)", "DEWALT", "DCD777C2", True, "", None),
        ("dewalt-dcd777c2", "mkt", "DeWalt DCD777C2 Brushless Compact Drill Driver Kit", "DEWALT", "DCD777C2", None, "", "Power Tools"),
    ]),
    "instant-pot": ("Home > Kitchen > Pressure Cookers", [
        ("instantpot-duo-6qt", "ref", "Instant Pot Duo 7-in-1 Electric Pressure Cooker, 6 Quart", "Instant Pot", "DUO60", True, "capacity=6 qt", None),
        ("instantpot-duo-6qt", "mkt", "Instant Pot DUO60 6 Qt 7-in-1 Multi-Use Programmable Pressure Cooker", "Instant Brands", "DUO60", None, "", "Kitchen"),
        ("instantpot-duo-8qt", "ref", "Instant Pot Duo 7-in-1 Electric Pressure Cooker, 8 Quart", "Instant Pot", "DUO80", True, "capacity=8 qt", None),
        ("instantpot-duo-8qt", "mkt", "Instant Pot DUO80 8 Qt 7-in-1 Pressure Cooker", "Instant Pot", "DUO80", None, "", "Kitchen"),
    ]),
    "starbucks": ("Grocery > Coffee > Ground Coffee", [
        ("sbux-pike-12oz", "ref", "Starbucks Ground Coffee, Pike Place Roast, Medium Roast, 12 oz Bag", "Starbucks", "", True, "", None),
        ("sbux-pike-12oz", "mkt", "Starbucks Pike Place Medium Roast Ground Coffee 12 Ounce", "Starbucks", "", None, "", "Coffee"),
        ("sbux-pike-28oz", "ref", "Starbucks Ground Coffee, Pike Place Roast, Medium Roast, 28 oz Bag", "Starbucks", "", True, "", None),
        ("sbux-veranda-12oz", "ref", "Starbucks Ground Coffee, Veranda Blend, Blonde Roast, 12 oz Bag", "Starbucks", "", True, "", None),
        ("sbux-veranda-12oz", "mkt", "Starbucks Veranda Blend Blonde Roast Ground Coffee 12oz", "Starbucks", "", None, "", "Coffee"),
    ]),
    "lacroix": ("Grocery > Beverages > Sparkling Water", [
        ("lacroix-lime-12pk", "ref", "LaCroix Sparkling Water, Lime, 12 fl oz cans (Pack of 12)", "LaCroix", "", True, "", None),
        ("lacroix-lime-12pk", "mkt", "La Croix Lime Sparkling Water 12 Pack 12 Fl Oz Cans", "LaCroix", "", None, "", "Beverages"),
        ("lacroix-lime-8pk", "ref", "LaCroix Sparkling Water, Lime, 12 fl oz cans (Pack of 8)", "LaCroix", "", True, "", None),
        ("lacroix-pamp-12pk", "ref", "LaCroix Sparkling Water, Pamplemousse, 12 fl oz cans (Pack of 12)", "LaCroix", "", True, "", None),
        ("lacroix-pamp-12pk", "mkt", "LaCroix Pamplemousse (Grapefruit) Sparkling Water, 12 Count, 12 oz cans", "LaCroix", "", None, "", "Beverages"),
    ]),
    "duracell": ("Household > Batteries", [
        ("duracell-aa-24", "ref", "Duracell Coppertop AA Batteries, 24 Count Pack", "Duracell", "MN1500", True, "", None),
        ("duracell-aa-24", "mkt", "Duracell AA Alkaline Batteries 24-Pack Coppertop", "Duracell", "", None, "", "Batteries"),
        ("duracell-aa-24", "mkt2", "Duracell Coppertop Double A Batteries - 24 Count", "", "", True, "", "Household"),
        ("duracell-aa-48", "ref", "Duracell Coppertop AA Batteries, 48 Count Pack", "Duracell", "MN1500", True, "", None),
        ("duracell-aaa-24", "ref", "Duracell Coppertop AAA Batteries, 24 Count Pack", "Duracell", "MN2400", True, "", None),
        ("duracell-aaa-24", "mkt", "Duracell AAA Coppertop Alkaline 24 Pack", "Duracell", "", None, "", "Batteries"),
    ]),
    "samsung-t7": ("Electronics > Storage > External SSD", [
        ("samsung-t7-1tb-gray", "ref", "SAMSUNG T7 Portable SSD 1TB USB 3.2 Gen 2, Titan Gray", "Samsung", "MU-PC1T0T/AM", True, "storage=1 TB;color=Titan Gray", None),
        ("samsung-t7-1tb-gray", "mkt", "Samsung T7 1TB Portable SSD Titan Grey MU-PC1T0T", "Samsung", "MU-PC1T0T", None, "", "Storage"),
        ("samsung-t7-2tb-gray", "ref", "SAMSUNG T7 Portable SSD 2TB USB 3.2 Gen 2, Titan Gray", "Samsung", "MU-PC2T0T/AM", True, "storage=2 TB;color=Titan Gray", None),
        ("samsung-t7-2tb-gray", "mkt", "Samsung T7 Portable SSD 2 TB - Titan Gray", "Samsung", "", None, "", "Storage"),
        ("samsung-t7shield-1tb", "ref", "SAMSUNG T7 Shield Portable SSD 1TB, Black", "Samsung", "MU-PE1T0S/AM", True, "storage=1 TB;color=Black", None),
    ]),
    "sandisk-microsd": ("Electronics > Storage > Memory Cards", [
        ("sandisk-ext-128", "ref", "SanDisk 128GB Extreme microSDXC UHS-I Memory Card with Adapter", "SanDisk", "SDSQXAA-128G-GN6MA", True, "storage=128 GB", None),
        ("sandisk-ext-128", "mkt", "SanDisk Extreme 128 GB microSD Card A2 U3 V30", "Sandisk", "SDSQXAA-128G", None, "", "Memory Cards"),
        ("sandisk-ext-256", "ref", "SanDisk 256GB Extreme microSDXC UHS-I Memory Card with Adapter", "SanDisk", "SDSQXAV-256G-GN6MA", True, "storage=256 GB", None),
        ("sandisk-ext-256", "mkt", "SanDisk Extreme 256GB microSDXC Card", "SanDisk", "", None, "", "Memory Cards"),
    ]),
    "logitech-mx": ("Electronics > Computer Accessories > Mice", [
        ("logi-mx-master-3s-graphite", "ref", "Logitech MX Master 3S Performance Wireless Mouse, Graphite", "Logitech", "910-006556", True, "color=Graphite", None),
        ("logi-mx-master-3s-graphite", "mkt", "Logitech MX Master 3S Wireless Mouse - Graphite", "Logitech International", "910-006556", None, "", "Mice"),
        ("logi-mx-master-3s-graphite", "mkt2", "MX Master 3S Logitech Ergonomic Mouse Graphite 8K DPI", "", "", None, "", "Computer Mice"),
        ("logi-mx-master-3-graphite", "ref", "Logitech MX Master 3 Advanced Wireless Mouse, Graphite", "Logitech", "910-005647", True, "color=Graphite", None),
        ("logi-mx-master-3s-pale", "ref", "Logitech MX Master 3S Performance Wireless Mouse, Pale Gray", "Logitech", "910-006558", True, "color=Pale Gray", None),
    ]),
    "nike-pegasus": ("Apparel > Shoes > Running", [
        ("pegasus40-m-10-black", "ref", "Nike Air Zoom Pegasus 40 Men's Road Running Shoes, Black/White, Size 10", "Nike", "DV3853-001", True, "size=10;color=Black", None),
        ("pegasus40-m-10-black", "mkt", "Nike Pegasus 40 Mens Running Shoe Black White Size 10", "Nike", "", None, "", "Shoes"),
        ("pegasus40-m-11-black", "ref", "Nike Air Zoom Pegasus 40 Men's Road Running Shoes, Black/White, Size 11", "Nike", "DV3853-001", True, "size=11;color=Black", None),
        ("pegasus41-m-10-black", "ref", "Nike Pegasus 41 Men's Road Running Shoes, Black/White, Size 10", "Nike", "FD2722-002", True, "size=10;color=Black", None),
        ("pegasus41-m-10-black", "mkt", "Nike Air Zoom Pegasus 41 Running Shoes Men Size 10 Black", "Nike", "", None, "", "Shoes"),
    ]),
    "cerave": ("Beauty > Skin Care > Moisturizers", [
        ("cerave-cream-16oz", "ref", "CeraVe Moisturizing Cream, Body and Face Moisturizer, 16 oz", "CeraVe", "", True, "", None),
        ("cerave-cream-16oz", "mkt", "CeraVe Moisturizing Cream 16 Ounce Daily Face and Body Moisturizer", "Cerave", "", None, "", "Skin Care"),
        ("cerave-cream-19oz", "ref", "CeraVe Moisturizing Cream, Body and Face Moisturizer, 19 oz", "CeraVe", "", True, "", None),
        ("cerave-lotion-19oz", "ref", "CeraVe Daily Moisturizing Lotion for Dry Skin, 19 oz", "CeraVe", "", True, "", None),
        ("cerave-lotion-19oz", "mkt", "CeraVe Daily Moisturizing Lotion 19 Ounce", "CeraVe", "", None, "", "Skin Care"),
    ]),
    "fitbit": ("Electronics > Wearables > Fitness Trackers", [
        ("fitbit-charge6-black", "ref", "Fitbit Charge 6 Fitness Tracker with Google apps, Obsidian/Black", "Fitbit", "GA05183-US", True, "color=Black", None),
        ("fitbit-charge6-black", "mkt", "Google Fitbit Charge 6 Activity Tracker Obsidian Black Band", "Fitbit", "", None, "", "Wearables"),
        ("fitbit-charge5-black", "ref", "Fitbit Charge 5 Advanced Fitness & Health Tracker, Black/Graphite", "Fitbit", "FB421BKBK-US", True, "color=Black", None),
        ("fitbit-charge5-black", "mkt", "Fitbit Charge 5 Fitness Tracker Black Graphite", "Fitbit", "FB421BKBK", None, "", "Wearables"),
    ]),
    "canon-r6": ("Electronics > Cameras > Mirrorless", [
        ("canon-r6ii-body", "ref", "Canon EOS R6 Mark II Mirrorless Camera (Body Only)", "Canon", "EOS R6 Mark II", True, "", None),
        ("canon-r6ii-body", "mkt", "Canon EOS R6 MKII Full Frame Mirrorless Camera Body", "Canon", "5666C002", None, "", "Cameras"),
        ("canon-r6ii-body", "mkt2", "Canon R6 Mark II Body Only Mirrorless", "Canon Inc", "", None, "", "Digital Cameras"),
        ("canon-r6-body", "ref", "Canon EOS R6 Full-Frame Mirrorless Camera (Body Only)", "Canon", "EOS R6", True, "", None),
        ("canon-r6-body", "mkt", "Canon EOS R6 Mirrorless Camera Body 20MP", "Canon", "4082C002", None, "", "Cameras"),
    ]),
    "dell-monitor": ("Electronics > Monitors", [
        ("dell-u2723qe", "ref", "Dell UltraSharp 27 4K USB-C Hub Monitor - U2723QE", "Dell", "U2723QE", True, "screen size=27 in", None),
        ("dell-u2723qe", "mkt", "Dell U2723QE UltraSharp 27\" 4K IPS Black USB-C Monitor", "Dell", "U2723QE", None, "", "Monitors"),
        ("dell-u2720q", "ref", "Dell UltraSharp 27 4K USB-C Monitor - U2720Q", "Dell", "U2720Q", True, "screen size=27 in", None),
        ("dell-u2720q", "mkt", "Dell U2720Q 27 Inch 4K UHD Monitor", "Dell Technologies", "U2720Q", None, "", "Monitors"),
    ]),
    "tplink": ("Electronics > Networking > Routers", [
        ("tplink-ax55", "ref", "TP-Link Archer AX55 AX3000 Dual Band Gigabit Wi-Fi 6 Router", "TP-Link", "Archer AX55", True, "", None),
        ("tplink-ax55", "mkt", "TP Link AX3000 WiFi 6 Router Archer AX55", "TP-LINK", "AX55", None, "", "Routers"),
        ("tplink-ax73", "ref", "TP-Link Archer AX73 AX5400 Dual Band Gigabit Wi-Fi 6 Router", "TP-Link", "Archer AX73", True, "", None),
        ("tplink-ax73", "mkt", "TP-Link AX5400 WiFi 6 Router (Archer AX73)", "TP-Link", "", None, "", "Routers"),
    ]),
    "jbl-flip": ("Electronics > Audio > Portable Speakers", [
        ("jbl-flip6-blue", "ref", "JBL Flip 6 Portable Waterproof Bluetooth Speaker, Blue", "JBL", "JBLFLIP6BLUAM", True, "color=Blue", None),
        ("jbl-flip6-blue", "mkt", "JBL FLIP 6 Bluetooth Speaker Waterproof Blue", "JBL", "", None, "", "Speakers"),
        ("jbl-flip6-black", "ref", "JBL Flip 6 Portable Waterproof Bluetooth Speaker, Black", "JBL", "JBLFLIP6BLKAM", True, "color=Black", None),
        ("jbl-flip6-black", "mkt", "JBL Flip6 Portable Speaker - Black", "JBL", "JBLFLIP6BLK", None, "", "Speakers"),
        ("jbl-flip5-blue", "ref", "JBL Flip 5 Waterproof Portable Bluetooth Speaker, Blue", "JBL", "JBLFLIP5BLUAM", True, "color=Blue", None),
    ]),
    "controllers": ("Electronics > Gaming > Controllers", [
        ("xbox-ctrl-carbon", "ref", "Xbox Core Wireless Controller - Carbon Black", "Microsoft", "QAT-00001", True, "color=Carbon Black", None),
        ("xbox-ctrl-carbon", "mkt", "Microsoft Xbox Wireless Controller Carbon Black", "Microsoft", "QAT-00001", None, "", "Video Games"),
        ("xbox-ctrl-robotwhite", "ref", "Xbox Core Wireless Controller - Robot White", "Microsoft", "QAS-00001", True, "color=Robot White", None),
        ("ps5-dualsense-white", "ref", "PlayStation DualSense Wireless Controller - White", "Sony", "CFI-ZCT1W", True, "color=White", None),
        ("ps5-dualsense-white", "mkt", "Sony PS5 DualSense Controller White", "Sony Interactive Entertainment", "", None, "", "Video Games"),
    ]),
    "purina": ("Pet Supplies > Dog Food", [
        ("purina-proplan-chicken-18lb", "ref", "Purina Pro Plan Adult Dry Dog Food, Shredded Blend Chicken & Rice, 18 lb Bag", "Purina Pro Plan", "", True, "", None),
        ("purina-proplan-chicken-18lb", "mkt", "Purina Pro Plan Shredded Blend Chicken and Rice Adult Dog Food 18 Pound", "Purina", "", None, "", "Dog Food"),
        ("purina-proplan-chicken-35lb", "ref", "Purina Pro Plan Adult Dry Dog Food, Shredded Blend Chicken & Rice, 35 lb Bag", "Purina Pro Plan", "", True, "", None),
        ("purina-proplan-chicken-35lb", "mkt", "Purina Pro Plan Chicken & Rice Shredded Blend 35 lbs", "Purina Pro Plan", "", None, "", "Dog Food"),
    ]),
    "tide": ("Household > Laundry > Detergent", [
        ("tide-pods-42", "ref", "Tide PODS Laundry Detergent Pacs, Original Scent, 42 Count", "Tide", "", True, "", None),
        ("tide-pods-42", "mkt", "Tide Pods Original 42 ct Laundry Detergent", "Procter & Gamble", "", None, "", "Laundry"),
        ("tide-pods-81", "ref", "Tide PODS Laundry Detergent Pacs, Original Scent, 81 Count", "Tide", "", True, "", None),
        ("tide-pods-81", "mkt", "Tide PODS Original Scent 81 Count", "Tide", "", True, "", "Laundry"),
    ]),
    "anker": ("Electronics > Chargers", [
        ("anker-735", "ref", "Anker 735 Charger (Nano II 65W) 3-Port GaN Fast Charger", "Anker", "A2667", True, "wattage=65 W", None),
        ("anker-735", "mkt", "Anker Nano II 65W 735 USB C Charger 3 Port", "Anker", "A2667", None, "", "Chargers"),
        ("anker-737", "ref", "Anker 737 Charger (GaNPrime 120W) 3-Port Fast Charger", "Anker", "A2148", True, "wattage=120 W", None),
        ("anker-737", "mkt", "Anker 737 GaNPrime 120W USB-C Charger", "Anker", "", None, "", "Chargers"),
    ]),
    "hydroflask": ("Home > Kitchen > Water Bottles", [
        ("hf-32-black", "ref", "Hydro Flask 32 oz Wide Mouth Water Bottle with Flex Cap, Black", "Hydro Flask", "W32BTS001", True, "color=Black", None),
        ("hf-32-black", "mkt", "Hydro Flask Wide Mouth Bottle 32 fl. oz. Black Flex Cap", "Hydro Flask", "W32BTS001", None, "", "Drinkware"),
        ("hf-40-black", "ref", "Hydro Flask 40 oz Wide Mouth Water Bottle with Flex Cap, Black", "Hydro Flask", "W40BTS001", True, "color=Black", None),
        ("hf-32-pacific", "ref", "Hydro Flask 32 oz Wide Mouth Water Bottle with Flex Cap, Pacific", "Hydro Flask", "W32BTS415", True, "color=Pacific", None),
    ]),
    "galaxy-s24": ("Electronics > Phones > Smartphones", [
        ("galaxy-s24-256-black", "ref", "Samsung Galaxy S24 256GB Onyx Black", "Samsung", "SM-S921U", True, "storage=256 GB;color=Onyx Black", None),
        ("galaxy-s24-256-black", "mkt", "SAMSUNG Galaxy S24 Cell Phone 256 GB Onyx Black Unlocked", "Samsung", "SM-S921U1", None, "", "Cell Phones"),
        ("galaxy-s24plus-256-black", "ref", "Samsung Galaxy S24+ 256GB Onyx Black", "Samsung", "SM-S926U", True, "storage=256 GB;color=Onyx Black", None),
        ("galaxy-s24plus-256-black", "mkt", "Samsung Galaxy S24 Plus 256GB Onyx Black Unlocked", "Samsung", "", None, "", "Cell Phones"),
    ]),
    "nespresso": ("Home > Kitchen > Coffee Makers", [
        ("vertuo-next-black", "ref", "Nespresso Vertuo Next Coffee and Espresso Machine by Breville, Matte Black", "Nespresso", "BNV550MTB", True, "color=Matte Black", None),
        ("vertuo-next-black", "mkt", "Breville Nespresso Vertuo Next Espresso Machine Black BNV550", "Breville", "BNV550MTB1BUC1", None, "", "Coffee Makers"),
        ("vertuo-next-gray", "ref", "Nespresso Vertuo Next Coffee and Espresso Machine by Breville, Light Grey", "Nespresso", "BNV550GRY", True, "color=Light Gray", None),
    ]),
    "ninja-blender": ("Home > Kitchen > Blenders", [
        ("ninja-bl610", "ref", "Ninja BL610 Professional 72 Oz Countertop Blender, 1000 Watt", "Ninja", "BL610", True, "wattage=1000 W", None),
        ("ninja-bl610", "mkt", "Ninja Professional Blender 1000W BL610 72oz Pitcher", "SharkNinja", "BL610", None, "", "Blenders"),
        ("ninja-bn701", "ref", "Ninja BN701 Professional Plus Blender, 1400 Peak Watts, 72 Oz", "Ninja", "BN701", True, "wattage=1400 W", None),
    ]),
    "levis": ("Apparel > Men > Jeans", [
        ("levis-501-32x32-rinse", "ref", "Levi's Men's 501 Original Fit Jeans, Rinse, 32W x 32L", "Levi's", "00501-0115", True, "size=32x32;color=Rinse", None),
        ("levis-501-32x32-rinse", "mkt", "Levis 501 Original Fit Mens Jeans Rinse Wash 32x32", "Levi Strauss", "", None, "", "Jeans"),
        ("levis-501-34x32-rinse", "ref", "Levi's Men's 501 Original Fit Jeans, Rinse, 34W x 32L", "Levi's", "00501-0115", True, "size=34x32;color=Rinse", None),
        ("levis-505-32x32-rinse", "ref", "Levi's Men's 505 Regular Fit Jeans, Rinse, 32W x 32L", "Levi's", "00505-1524", True, "size=32x32;color=Rinse", None),
    ]),
    # Identifier edge cases: same product with a regional GTIN; a seller copying the wrong GTIN.
    "edge-cases": ("Electronics > Audio > Headphones", [
        ("bose-qc-ultra-black", "ref", "Bose QuietComfort Ultra Wireless Noise Cancelling Headphones, Black", "Bose", "880066-0100", True, "color=Black", None),
        ("bose-qc-ultra-black", "mkt", "Bose QuietComfort Ultra Headphones Black (EU import)", "Bose", "880066-0100", "key:bose-qc-ultra-black-eu-pack", "", "Headphones"),
        ("bose-qc-ultra-white", "ref", "Bose QuietComfort Ultra Wireless Noise Cancelling Headphones, White Smoke", "Bose", "880066-0200", True, "color=White Smoke", None),
        ("bose-qc-ultra-white", "mkt", "Bose QC Ultra Headphones White Smoke", "Bose", "", "key:bose-qc-ultra-black", "", "Headphones"),
    ]),
}


def main() -> None:
    rows = ["id\tsource\ttruth_key\tfamily\ttitle\tbrand\tmodel\tcategory\tgtin\tattributes"]
    counters = {}
    for family, (category, items) in FAMILIES.items():
        for truth, source, title, brand, model, gtin, attrs, category_override in items:
            counters[family] = counters.get(family, 0) + 1
            listing_id = f"{family}-{counters[family]:02d}"
            if gtin is True:
                value = gtin13(truth)
            elif isinstance(gtin, str) and gtin.startswith("key:"):
                value = gtin13(gtin[4:])
            else:
                value = ""
            rows.append("\t".join([listing_id, source, truth, family, title, brand, model,
                                   category_override or category, value, attrs]))
    OUT.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"wrote {len(rows) - 1} listings to {OUT}")


if __name__ == "__main__":
    main()
