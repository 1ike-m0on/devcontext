import { betaFormatter } from "../src/formatter.js";

test("beta formatter", () => {
  expect(betaFormatter(["a", "b"])).toBe("a-b");
});
