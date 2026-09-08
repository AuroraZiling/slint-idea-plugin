use std::{collections::HashMap, path::PathBuf};
#[path = "build/i18n.rs"]
mod translations;
fn main() {
    let catalog = translations::generate();
    let config = slint_build::CompilerConfiguration::new()
        .with_library_paths(HashMap::from([
            ("i18n".into(), catalog),
            ("lucide".to_string(), PathBuf::from(lucide_slint::lib())),
            ("shapes".into(), PathBuf::from(renamed_shapes::entry())),
        ]))
        .embed_resources(slint_build::EmbedResourcesKind::EmbedFiles);
    slint_build::compile_with_config("ui/app.slint", config).expect("Compile Slint UI");
}
