use manifest_rust::manifest_name;

#[test]
fn trims_manifest_name() {
    assert_eq!(manifest_name(" sample crate "), "sample-crate");
}
