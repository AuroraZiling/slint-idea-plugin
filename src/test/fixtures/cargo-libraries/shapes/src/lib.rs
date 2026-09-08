/// A differently named dependency, function, and Slint file.
pub fn entry() -> String {
    concat!(env!("CARGO_MANIFEST_DIR"), "/geometry.slint").to_string()
}
