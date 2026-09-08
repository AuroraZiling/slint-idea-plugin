use std::{collections::HashMap as Libraries, path::PathBuf as FilePath};
fn main() {
    let manifest_dir = FilePath::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let config = slint_build::CompilerConfiguration::new().with_library_paths(Libraries::from([
        ("i18n".into(), manifest_dir.join("ui/different.slint")),
        ("translations".into(), "ui/different.slint".into()),
        ("folder".into(), FilePath::from("ui")),
    ]));
    slint_build::compile_with_config("ui/app.slint", config).unwrap();
}
