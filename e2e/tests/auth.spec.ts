import { expect, test } from "@playwright/test";
import { attemptLogin, PASSWORD, register, uniqueEmail } from "./helpers";

test.describe("authentication", () => {
  test("a new account can register and lands on the upload screen", async ({ page }) => {
    const email = uniqueEmail("register");

    await register(page, email);

    await expect(page.getByTestId("current-user-email")).toHaveText(email);
    await expect(page.getByTestId("notice-banner")).toHaveAttribute("data-notice-kind", "success");
    await expect(page.getByTestId("upload-submit")).toBeDisabled();
  });

  test("registering an email twice shows an error and stays on the auth screen", async ({ page }) => {
    const email = uniqueEmail("duplicate");
    await register(page, email);

    // Log out, then try to register the same address again.
    await page.getByTestId("logout-button").click();
    await page.getByTestId("auth-mode-register").click();
    await page.getByTestId("auth-email").fill(email);
    await page.getByTestId("auth-password").fill(PASSWORD);
    await page.getByTestId("auth-submit").click();

    await expect(page.getByTestId("notice-banner")).toHaveAttribute("data-notice-kind", "error");
    await expect(page.getByTestId("auth-form")).toBeVisible();
    await expect(page.getByTestId("upload-form")).toHaveCount(0);
  });

  test("an existing account can log in again", async ({ page }) => {
    const email = uniqueEmail("login");
    await register(page, email);
    await page.getByTestId("logout-button").click();
    await expect(page.getByTestId("auth-form")).toBeVisible();

    await attemptLogin(page, email, PASSWORD);

    await expect(page.getByTestId("upload-form")).toBeVisible();
    await expect(page.getByTestId("current-user-email")).toHaveText(email);
  });

  test("logging in with the wrong password is refused", async ({ page }) => {
    const email = uniqueEmail("wrong-password");
    await register(page, email);
    await page.getByTestId("logout-button").click();

    await attemptLogin(page, email, "definitely-wrong");

    await expect(page.getByTestId("notice-banner")).toHaveAttribute("data-notice-kind", "error");
    await expect(page.getByTestId("auth-form")).toBeVisible();
  });

  test("logging out clears the session, including after a reload", async ({ page }) => {
    const email = uniqueEmail("logout");
    await register(page, email);

    await page.getByTestId("logout-button").click();

    await expect(page.getByTestId("auth-form")).toBeVisible();
    await expect(page.getByTestId("logout-button")).toHaveCount(0);

    // The token is really gone, not just hidden by client state.
    await page.reload();
    await expect(page.getByTestId("auth-form")).toBeVisible();
  });

  test("the session survives a page reload", async ({ page }) => {
    const email = uniqueEmail("persist");
    await register(page, email);

    await page.reload();

    await expect(page.getByTestId("upload-form")).toBeVisible();
    await expect(page.getByTestId("current-user-email")).toHaveText(email);
  });
});
