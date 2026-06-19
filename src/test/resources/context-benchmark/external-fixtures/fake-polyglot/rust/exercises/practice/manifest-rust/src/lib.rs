pub fn manifest_name(input: &str) -> String {
    input.trim().replace(' ', "-")
}
