import { test, expect } from "@playwright/test";

test("a public sync address stays in the form after discovery, sign-in, and reload", async ({page}) => {
  const alias = "https://tasks.example", primary = "https://native.example";
  await page.route("**/.well-known/vervetwodo-sync", route =>
    route.fulfill({json:{syncOrigin: primary, publicOrigin: alias}}));
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/login") {
      expect(url.searchParams.get("workspace")).toBe(alias);
      return route.fulfill({json:{ok:true, syncOrigin:primary}});
    }
    if (url.pathname === "/api/events") return route.fulfill({contentType:"text/event-stream",body:""});
    return route.fulfill({status:503,json:{error:"offline"}});
  });
  await page.goto("/");
  await page.locator(".sync-pill").click();
  const field = page.getByLabel("Sync URL",{exact:true});
  await expect(field).toHaveValue(alias);
  await field.fill(alias + "/");
  await page.getByLabel("Access code",{exact:true}).fill("test-only-code");
  await page.locator("form button.primary").click();
  await expect(page.getByLabel("Access code",{exact:true})).toHaveValue("");
  await expect(field).toHaveValue(alias + "/");
  await page.reload();
  await page.locator(".sync-pill").click();
  await expect(field).toHaveValue(alias + "/");
});

test("changing sync URL keeps pending edits and saved tasks with their original workspace", async ({page}) => {
  const primary="https://primary.example", secondary="https://secondary.example";
  await page.route("**/.well-known/vervetwodo-sync", route=>route.fulfill({json:{syncOrigin:primary}}));
  await page.route("**/api/**", async route=>{
    const url=new URL(route.request().url());
    if(url.pathname==="/api/login")return route.fulfill({json:{ok:true,syncOrigin:url.searchParams.get("workspace")}});
    if(url.pathname==="/api/events")return route.fulfill({contentType:"text/event-stream",body:""});
    return route.fulfill({status:503,json:{error:"offline"}});
  });
  await page.goto("/");
  await page.locator(".new-task").click();
  await page.getByLabel("Title",{exact:true}).fill("Private sample on the first server");
  await page.getByRole("button",{name:"Save",exact:true}).click();
  await page.getByRole("button",{name:"Close",exact:true}).last().click();
  await page.locator(".sync-pill").click();
  const field=page.getByLabel("Sync URL",{exact:true});
  await expect(field).toHaveValue(primary);
  const connect=async(url:string)=>{
    await field.fill(url);
    await page.getByLabel("Access code",{exact:true}).fill("test-only-code");
    await page.locator("form button.primary").click();
    await expect(page.getByLabel("Access code",{exact:true})).toHaveValue("");
  };
  await connect(primary);
  await connect(secondary);
  await page.getByRole("button",{name:"Close",exact:true}).last().click();
  await expect(page.locator(".task-row")).toHaveCount(0);
  await page.reload();
  await page.locator(".sync-pill").click();
  await expect(field).toHaveValue(secondary);
  await connect(primary);
  await page.getByRole("button",{name:"Close",exact:true}).last().click();
  await expect(page.locator(".task-row")).toContainText("Private sample on the first server");
});
