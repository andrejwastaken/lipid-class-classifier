import { expect, type Page } from "@playwright/test";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));

// Fixtures produced by `python3 testdata/generate_fixtures.py`. 
export const TESTDATA_DIR = path.resolve(here, "..", "..", "testdata");
export const SAMPLE_MZML = path.join(TESTDATA_DIR, "sample.mzML");
export const WRONG_EXTENSION = path.join(TESTDATA_DIR, "wrong-extension.txt");

export const PASSWORD = "password123";

// A fresh address per test, so the suite is re-runnable against a persistent database. 
export function uniqueEmail(prefix = "e2e"): string {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  return `${prefix}-${suffix}@example.com`;
}

async function submitAuthForm(page: Page, email: string, password: string) {
  await page.getByTestId("auth-email").fill(email);
  await page.getByTestId("auth-password").fill(password);
  await page.getByTestId("auth-submit").click();
}

// Registers a new account and waits for the authenticated upload screen. 
export async function register(page: Page, email: string, password = PASSWORD) {
  await page.goto("/");
  await page.getByTestId("auth-mode-register").click();
  await submitAuthForm(page, email, password);
  await expect(page.getByTestId("upload-form")).toBeVisible();
}

// Logs in with an existing account, without asserting the outcome. 
export async function attemptLogin(page: Page, email: string, password: string) {
  await page.goto("/");
  await page.getByTestId("auth-mode-login").click();
  await submitAuthForm(page, email, password);
}
