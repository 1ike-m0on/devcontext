import { alphaName } from "./main.js";

test("alpha name", () => {
  expect(alphaName(" demo ")).toBe("DEMO");
});
