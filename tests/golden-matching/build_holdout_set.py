#!/usr/bin/env python3
"""Builds tests/golden-matching/golden-holdout.tsv — a HOLDOUT evaluation set.

Protocol: this file was authored after the first evaluation of rules-v1 on golden-listings.tsv (the
development set) and BEFORE any matcher changes were made in response to that evaluation. It contains only
product families that do not appear in the development set. It must not be used to tune the matcher; it is
evaluated once per matcher version and reported as-is (see docs/benchmarking.md).

Same conventions as build_golden_set.py (synthetic GS1 200-299 GTINs, nominative brand use).
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from build_golden_set import gtin13  # noqa: E402

OUT = pathlib.Path(__file__).with_name("golden-holdout.tsv")

FAMILIES = {
    "macbook": ("Electronics > Computers > Laptops", [
        ("mba13-m3-8-256-midnight", "ref", "Apple MacBook Air 13-inch M3 chip 8GB RAM 256GB SSD - Midnight", "Apple", "MRXV3LL/A", True, "ram=8 GB;storage=256 GB;color=Midnight", None),
        ("mba13-m3-8-256-midnight", "mkt", "Apple 2024 MacBook Air 13\" Laptop M3 8GB 256GB Midnight", "Apple", "MRXV3LL/A", None, "", "Laptops"),
        ("mba13-m3-8-256-midnight", "mkt2", "MacBook Air 13.6 inch M3 (8GB/256GB) Midnight MRXV3", "", "", None, "", "Computers"),
        ("mba13-m3-16-512-midnight", "ref", "Apple MacBook Air 13-inch M3 chip 16GB RAM 512GB SSD - Midnight", "Apple", "MXCV3LL/A", True, "ram=16 GB;storage=512 GB;color=Midnight", None),
        ("mbp14-m3-8-512-gray", "ref", "Apple MacBook Pro 14-inch M3 chip 8GB RAM 512GB SSD - Space Gray", "Apple", "MTL73LL/A", True, "ram=8 GB;storage=512 GB;color=Space Gray", None),
        ("mbp14-m3-8-512-gray", "mkt", "Apple MacBook Pro 14 inch M3 8GB 512GB Space Grey", "Apple", "", None, "", "Laptops"),
    ]),
    "bose-soundlink": ("Electronics > Audio > Portable Speakers", [
        ("sl-flex-black", "ref", "Bose SoundLink Flex Bluetooth Portable Speaker, Black", "Bose", "865983-0100", True, "color=Black", None),
        ("sl-flex-black", "mkt", "Bose SoundLink Flex Wireless Waterproof Speaker - Black", "Bose Corporation", "", None, "", "Speakers"),
        ("sl-flex-blue", "ref", "Bose SoundLink Flex Bluetooth Portable Speaker, Stone Blue", "Bose", "865983-0200", True, "color=Stone Blue", None),
        ("sl-flex-blue", "mkt", "Bose SoundLink Flex Speaker Stone Blue", "Bose", "865983-0200", None, "", "Speakers"),
        ("sl-micro-black", "ref", "Bose SoundLink Micro Bluetooth Speaker, Black", "Bose", "783342-0100", True, "color=Black", None),
    ]),
    "keurig": ("Home > Kitchen > Coffee Makers", [
        ("kmini-black", "ref", "Keurig K-Mini Single Serve Coffee Maker, Black", "Keurig", "K-Mini", True, "color=Black", None),
        ("kmini-black", "mkt", "Keurig K Mini Single-Serve K-Cup Pod Coffee Brewer Black", "Keurig Dr Pepper", "", None, "", "Coffee Machines"),
        ("kminiplus-black", "ref", "Keurig K-Mini Plus Single Serve Coffee Maker, Black", "Keurig", "K-Mini Plus", True, "color=Black", None),
        ("kminiplus-black", "mkt", "Keurig K-Mini Plus Coffee Maker with Pod Storage, Black", "Keurig", "", None, "", "Coffee Machines"),
    ]),
    "sonicare": ("Beauty > Oral Care > Electric Toothbrushes", [
        ("sonicare-4100-white", "ref", "Philips Sonicare ProtectiveClean 4100 Rechargeable Electric Toothbrush, White", "Philips Sonicare", "HX6817/01", True, "color=White", None),
        ("sonicare-4100-white", "mkt", "Philips Sonicare 4100 Electric Toothbrush HX6817 White", "Philips", "HX6817/01", None, "", "Oral Care"),
        ("sonicare-5100-white", "ref", "Philips Sonicare ProtectiveClean 5100 Rechargeable Electric Toothbrush, White", "Philips Sonicare", "HX6857/11", True, "color=White", None),
        ("sonicare-5100-white", "mkt", "Philips Sonicare ProtectiveClean 5100 Toothbrush (White)", "Philips", "", None, "", "Oral Care"),
    ]),
    "crayola": ("Toys > Arts & Crafts > Crayons", [
        ("crayola-24", "ref", "Crayola Crayons, 24 Count, Assorted Colors", "Crayola", "52-3024", True, "", None),
        ("crayola-24", "mkt", "Crayola 24 Ct Crayons Classic Colors", "Crayola", "", None, "", "Art Supplies"),
        ("crayola-24", "mkt2", "Crayola Crayon Set 24-Pack", "", "", True, "", "School Supplies"),
        ("crayola-64", "ref", "Crayola Crayons, 64 Count with Built-in Sharpener", "Crayola", "52-0064", True, "", None),
        ("crayola-64", "mkt", "Crayola 64 Count Crayon Box with Sharpener", "Crayola", "", None, "", "Art Supplies"),
    ]),
    "garmin": ("Electronics > Wearables > Smartwatches", [
        ("fr265-black", "ref", "Garmin Forerunner 265 Running Smartwatch, Black/Powder Gray", "Garmin", "010-02810-00", True, "color=Black", None),
        ("fr265-black", "mkt", "Garmin Forerunner 265 GPS Running Watch AMOLED Black", "Garmin", "010-02810-00", None, "", "Smartwatches"),
        ("fr965-black", "ref", "Garmin Forerunner 965 Running Smartwatch, Black/Powder Gray", "Garmin", "010-02809-00", True, "color=Black", None),
        ("fr965-black", "mkt", "Garmin Forerunner 965 Premium GPS Running Watch Black", "Garmin", "", None, "", "Smartwatches"),
    ]),
    "dyson": ("Home > Appliances > Vacuums", [
        ("dyson-v8", "ref", "Dyson V8 Cordless Vacuum Cleaner, Nickel/Silver", "Dyson", "V8", True, "color=Silver", None),
        ("dyson-v8", "mkt", "Dyson V8 Cordless Stick Vacuum Silver Nickel", "Dyson", "", None, "", "Vacuums"),
        ("dyson-v15", "ref", "Dyson V15 Detect Cordless Vacuum Cleaner, Yellow/Nickel", "Dyson", "V15 Detect", True, "color=Yellow", None),
        ("dyson-v15", "mkt", "Dyson V15 Detect Cordless Stick Vacuum", "Dyson", "", None, "", "Vacuums"),
    ]),
    "ring": ("Electronics > Smart Home > Doorbells", [
        ("ring-vd-2nd", "ref", "Ring Video Doorbell (2nd Generation), 1080p HD Video, Satin Nickel", "Ring", "", True, "", None),
        ("ring-vd-2nd", "mkt", "Ring Video Doorbell 2nd Gen 1080p Satin Nickel", "Ring", "", None, "", "Security"),
        ("ring-vd-pro2", "ref", "Ring Video Doorbell Pro 2, Head-to-Toe 1536p HD Video", "Ring", "", True, "", None),
        ("ring-vd-pro2", "mkt", "Ring Video Doorbell Pro 2 Hardwired 1536p", "Ring", "", None, "", "Security"),
    ]),
    "coke": ("Grocery > Beverages > Soda", [
        ("coke-12oz-12pk", "ref", "Coca-Cola Soda Soft Drink, 12 fl oz Cans, 12 Pack", "Coca-Cola", "", True, "", None),
        ("coke-12oz-12pk", "mkt", "Coca Cola Classic 12 Pack 12oz Cans", "The Coca-Cola Company", "", None, "", "Soda"),
        ("dietcoke-12oz-12pk", "ref", "Diet Coke Soda Soft Drink, 12 fl oz Cans, 12 Pack", "Coca-Cola", "", True, "", None),
        ("dietcoke-12oz-12pk", "mkt", "Diet Coke 12 Pack 12 fl oz Cans", "Diet Coke", "", None, "", "Soda"),
    ]),
    "kitchenaid": ("Home > Kitchen > Stand Mixers", [
        ("ka-artisan-5qt-red", "ref", "KitchenAid Artisan Series 5 Quart Tilt-Head Stand Mixer, Empire Red", "KitchenAid", "KSM150PSER", True, "color=Empire Red", None),
        ("ka-artisan-5qt-red", "mkt", "Kitchen Aid KSM150PSER Artisan 5-Qt Stand Mixer Empire Red", "Kitchen Aid", "KSM150PSER", None, "", "Mixers"),
        ("ka-artisan-5qt-black", "ref", "KitchenAid Artisan Series 5 Quart Tilt-Head Stand Mixer, Onyx Black", "KitchenAid", "KSM150PSOB", True, "color=Onyx Black", None),
        ("ka-artisan-5qt-black", "mkt", "KitchenAid Artisan Stand Mixer 5 Qt Onyx Black KSM150PSOB", "KitchenAid", "", None, "", "Mixers"),
        ("ka-classic-4.5qt-white", "ref", "KitchenAid Classic Series 4.5 Quart Tilt-Head Stand Mixer, White", "KitchenAid", "K45SSWH", True, "color=White", None),
    ]),
    "lenovo": ("Electronics > Computers > Laptops", [
        ("ideapad3-15-8-512", "ref", "Lenovo IdeaPad 3 15.6\" Laptop, Intel Core i5, 8GB RAM, 512GB SSD", "Lenovo", "82RK00EVUS", True, "screen size=15.6 in;ram=8 GB;storage=512 GB", None),
        ("ideapad3-15-8-512", "mkt", "Lenovo IdeaPad 3 15.6 inch Laptop i5 8GB/512GB SSD 82RK00EVUS", "Lenovo", "82RK00EVUS", None, "", "Laptops"),
        ("ideapad3-14-8-512", "ref", "Lenovo IdeaPad 3 14\" Laptop, Intel Core i5, 8GB RAM, 512GB SSD", "Lenovo", "82RJ0007US", True, "screen size=14 in;ram=8 GB;storage=512 GB", None),
    ]),
}


def main() -> None:
    rows = ["id\tsource\ttruth_key\tfamily\ttitle\tbrand\tmodel\tcategory\tgtin\tattributes"]
    counters = {}
    for family, (category, items) in FAMILIES.items():
        for truth, source, title, brand, model, gtin, attrs, category_override in items:
            counters[family] = counters.get(family, 0) + 1
            value = gtin13(truth) if gtin is True else ""
            rows.append("\t".join([f"{family}-{counters[family]:02d}", source, truth, family, title, brand, model,
                                   category_override or category, value, attrs]))
    OUT.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"wrote {len(rows) - 1} listings to {OUT}")


if __name__ == "__main__":
    main()
