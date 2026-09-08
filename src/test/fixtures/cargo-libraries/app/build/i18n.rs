use std::{collections::BTreeMap, path::PathBuf};
pub fn generate() -> PathBuf {
    println!("cargo:rerun-if-changed=i18n/en.json");
    println!("cargo:rerun-if-changed=i18n/zh-CN.json");
    let en: BTreeMap<String, String> = serde_json::from_str(&std::fs::read_to_string("i18n/en.json").unwrap()).unwrap();
    let zh: BTreeMap<String, String> = serde_json::from_str(&std::fs::read_to_string("i18n/zh-CN.json").unwrap()).unwrap();
    assert_eq!(en.keys().collect::<Vec<_>>(), zh.keys().collect::<Vec<_>>());
    let mut slint = String::from("export struct TranslationText {\n");
    let mut constants = String::new();
    let mut values = String::new();
    for (key, value) in &en {
        assert!(key.chars().all(|c| c.is_ascii_alphabetic() || c == '-'));
        slint.push_str(&format!("    {key}: string,\n"));
        constants.push_str(&format!("pub const {}: &str = {:?};\n", key.replace('-', "_").to_uppercase(), key));
        values.push_str(&format!("// {} = {} / {}\n", key, value, zh[key]));
    }
    slint.push_str("}\nexport global I18n { in-out property <TranslationText> text: {\n");
    for (key, value) in &en { slint.push_str(&format!("    {key}: {:?},\n", value)); }
    slint.push_str("}; }\n");
    let out = PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    std::fs::write(out.join("i18n.slint"), slint).unwrap();
    std::fs::write(out.join("i18n_bindings.rs"), constants + &values).unwrap();
    out.join("i18n.slint")
}
