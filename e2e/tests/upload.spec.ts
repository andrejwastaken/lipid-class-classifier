import { expect, test } from "@playwright/test";
import { register, SAMPLE_MZML, uniqueEmail, WRONG_EXTENSION } from "./helpers";

test.describe("upload and prediction", () => {
  test("an mzML upload runs through the worker and renders a prediction", async ({ page }) => {
    await register(page, uniqueEmail("upload"));

    // The file input is hidden behind a styled button; setInputFiles drives it directly.
    await page.getByTestId("file-input").setInputFiles(SAMPLE_MZML);
    await expect(page.getByTestId("selected-file-name")).toHaveText("sample.mzML");
    await expect(page.getByTestId("upload-submit")).toBeEnabled();

    await page.getByTestId("upload-submit").click();

    // The job is accepted and starts life as PENDING.
    await expect(page.getByTestId("notice-banner")).toHaveAttribute("data-notice-kind", "success");
    await expect(page.getByTestId("status-job-id")).not.toHaveText("No active job");

    // Polling carries it through the lifecycle; the result screen appears on DONE or FAILED.
    await expect(page.getByTestId("result-screen")).toBeVisible({ timeout: 75_000 });
    await expect(page.getByTestId("status-pill")).toHaveAttribute("data-job-status", "DONE");

    // The prediction itself is rendered.
    const predictedClass = page.getByTestId("result-predicted-class");
    await expect(predictedClass).toBeVisible();
    await expect(predictedClass).not.toHaveText("Unknown");
    await expect(predictedClass).not.toHaveText("");

    await expect(page.getByTestId("result-probability")).toHaveText(/^\d+\.\d{2}%$/);
    await expect(page.getByTestId("result-confidence")).toHaveText(/Confident|Low confidence/);
    await expect(page.getByTestId("result-job-id")).not.toHaveText("");
  });

  test("a non mzML file is rejected and no result screen appears", async ({ page }) => {
    await register(page, uniqueEmail("reject"));

    // The input carries accept=".mzML", but that only filters the OS picker - the real guard
    // is server side, which is what this exercises.
    await page.getByTestId("file-input").setInputFiles(WRONG_EXTENSION);
    await expect(page.getByTestId("selected-file-name")).toHaveText("wrong-extension.txt");

    await page.getByTestId("upload-submit").click();

    await expect(page.getByTestId("notice-banner")).toHaveAttribute("data-notice-kind", "error");
    await expect(page.getByTestId("result-screen")).toHaveCount(0);
    await expect(page.getByTestId("status-pill")).toHaveAttribute("data-job-status", "");
  });

  test("uploading a second file after a finished job starts a fresh job", async ({ page }) => {
    await register(page, uniqueEmail("second"));

    await page.getByTestId("file-input").setInputFiles(SAMPLE_MZML);
    await page.getByTestId("upload-submit").click();
    await expect(page.getByTestId("result-screen")).toBeVisible({ timeout: 75_000 });

    const firstJobId = await page.getByTestId("result-job-id").textContent();

    await page.getByTestId("new-upload-button").click();
    await expect(page.getByTestId("upload-form")).toBeVisible();
    await expect(page.getByTestId("selected-file-name")).toHaveText("No file selected");

    await page.getByTestId("file-input").setInputFiles(SAMPLE_MZML);
    await page.getByTestId("upload-submit").click();
    await expect(page.getByTestId("result-screen")).toBeVisible({ timeout: 75_000 });

    const secondJobId = await page.getByTestId("result-job-id").textContent();
    expect(secondJobId).not.toEqual(firstJobId);
  });

  test("an in-flight job is restored after a page reload", async ({ page }) => {
    await register(page, uniqueEmail("resume"));

    await page.getByTestId("file-input").setInputFiles(SAMPLE_MZML);
    await page.getByTestId("upload-submit").click();
    await expect(page.getByTestId("status-job-id")).not.toHaveText("No active job");

    // The active job id is persisted, so a reload keeps polling the same job.
    await page.reload();

    await expect(page.getByTestId("result-screen")).toBeVisible({ timeout: 75_000 });
    await expect(page.getByTestId("status-pill")).toHaveAttribute("data-job-status", "DONE");
  });
});
