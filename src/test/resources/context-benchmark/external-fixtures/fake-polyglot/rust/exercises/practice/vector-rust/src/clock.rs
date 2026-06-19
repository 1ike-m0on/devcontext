pub fn clock_vector(hour: i32, minute: i32) -> (i32, i32) {
    (hour.rem_euclid(24), minute.rem_euclid(60))
}
