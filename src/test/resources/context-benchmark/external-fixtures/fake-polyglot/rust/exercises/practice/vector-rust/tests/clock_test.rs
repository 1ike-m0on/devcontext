use vector_rust::clock_vector;

#[test]
fn wraps_clock() {
    assert_eq!(clock_vector(25, 61), (1, 1));
}
