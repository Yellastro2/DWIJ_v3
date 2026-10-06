using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

/// <summary>Изолированное окно VK и сброс его профиля; секреты передаются только через приватный IPC.</summary>
internal static class VkWebView2Host
{
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    private static readonly object OutputLock = new object();

    /// <summary>Проверяет Runtime, запускает вход или невидимый сброс собственного профиля под общим mutex.</summary>
    [STAThread]
    private static int Main(string[] args)
    {
        Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true });
        Console.SetIn(new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false)));
        try
        {
            string version;
            try { version = CoreWebView2Environment.GetAvailableBrowserVersionString(); }
            catch (WebView2RuntimeNotFoundException)
            {
                Emit(new { type = "error", code = "runtime_missing" });
                return 2;
            }
            if (args.Length == 1 && args[0] == "--probe")
            {
                Emit(new { type = "runtime", available = true, version = version });
                return 0;
            }
            var reset = args.Length == 3 && args[0] == "--reset";
            var offset = reset ? 1 : 0;
            if (args.Length != offset + 2 || args[offset] != "--user-data" || !Path.IsPathRooted(args[offset + 1]))
            {
                Emit(new { type = "error", code = "launch_failed" });
                return 3;
            }
            var profile = Path.GetFullPath(args[offset + 1]);
            string mutexName;
            using (var hash = SHA256.Create())
                mutexName = "Local\\DwijVkWebView2_" + BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(profile.ToLowerInvariant()))).Replace("-", "");
            using (var singleWindow = new Mutex(false, mutexName))
            {
                bool acquired;
                try { acquired = singleWindow.WaitOne(0); }
                catch (AbandonedMutexException) { acquired = true; }
                if (!acquired)
                {
                    Emit(new { type = "error", code = "already_open" });
                    return 4;
                }
                try
                {
                    Directory.CreateDirectory(profile);
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    if (reset)
                    {
                        using (var window = new ResetWindow(profile)) Application.Run(window);
                    }
                    else
                    {
                        using (var window = new LoginWindow(profile)) Application.Run(window);
                    }
                }
                finally { singleWindow.ReleaseMutex(); }
            }
            return 0;
        }
        catch
        {
            // Exception.Message и stack trace могут содержать URL или приватные параметры.
            Emit(new { type = "error", code = "launch_failed" });
            return 1;
        }
    }

    /// <summary>Пишет одну JSON-строку только в приватный канал IPC, без файлов и диагностических логов.</summary>
    internal static void Emit(object value)
    {
        lock (OutputLock) Console.Out.WriteLine(Json.Serialize(value));
    }

    /// <summary>Разрешает только HTTPS VK и его поддомены; HTTP, userInfo и сторонние сайты отклоняются.</summary>
    internal static bool IsVkUrl(string value)
    {
        Uri uri;
        if (!Uri.TryCreate(value, UriKind.Absolute, out uri)) return false;
        var host = uri.Host.ToLowerInvariant();
        return uri.Scheme == "https" && uri.Port == 443 && String.IsNullOrEmpty(uri.UserInfo) &&
            (host == "vk.ru" || host.EndsWith(".vk.ru") || host == "vk.com" || host.EndsWith(".vk.com"));
    }

    /// <summary>Невидимый WebView2 очищает профиль через API без навигации к сайту или удаления файлов.</summary>
    private sealed class ResetWindow : Form
    {
        private readonly string profile;
        private readonly WebView2 browser = new WebView2();

        /// <summary>Подготавливает невидимое окно без taskbar-кнопки; очистка начинается после создания UI handle.</summary>
        internal ResetWindow(string profile)
        {
            this.profile = profile;
            ShowInTaskbar = false;
            Opacity = 0;
            ClientSize = new Size(1, 1);
            StartPosition = FormStartPosition.Manual;
            Location = new Point(-32000, -32000);
            browser.Dock = DockStyle.Fill;
            Controls.Add(browser);
            Shown += async (sender, args) => await ResetProfile();
        }

        /// <summary>Ждёт очистки cookies, хранилища и кеша профиля; SDK payload и браузеры Chrome/Edge не затрагивает.</summary>
        private async Task ResetProfile()
        {
            try
            {
                var environment = await CoreWebView2Environment.CreateAsync(null, profile);
                await browser.EnsureCoreWebView2Async(environment);
                await browser.CoreWebView2.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile);
                Emit(new { type = "reset" });
            }
            catch { Emit(new { type = "error", code = "reset_failed" }); }
            finally { Close(); }
        }
    }

    /// <summary>WebView2 с собственной папкой cookies; управление проверкой приходит от Kotlin через stdin.</summary>
    private sealed class LoginWindow : Form
    {
        private readonly string profile;
        private readonly WebView2 browser = new WebView2();
        private readonly Label status = new Label();
        private readonly Button complete = new Button();
        private readonly Button reload = new Button();
        private bool checking;
        private bool readingCookies;
        private bool parentClosing;
        private Task parentReader;
        private string attemptedP;
        private string attemptedSid;

        /// <summary>Создаёт окно, панели действий и события, не запуская браузер до Shown.</summary>
        internal LoginWindow(string profile)
        {
            this.profile = profile;
            Text = "Движ — вход через сайт VK";
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(960, 720);
            MinimumSize = new Size(560, 500);
            BackColor = Color.FromArgb(12, 14, 22);
            ForeColor = Color.White;
            status.Dock = DockStyle.Top;
            status.Height = 50;
            status.Padding = new Padding(10);
            status.Text = "Открываем сайт VK…";
            var actions = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 48, Padding = new Padding(8) };
            complete.Text = "Я вошёл";
            complete.AutoSize = true;
            complete.Enabled = false;
            complete.Click += async (sender, args) => await ReadCookies(true);
            reload.Text = "Обновить страницу";
            reload.AutoSize = true;
            reload.Enabled = false;
            reload.Click += (sender, args) => browser.Reload();
            actions.Controls.Add(complete);
            actions.Controls.Add(reload);
            browser.Dock = DockStyle.Fill;
            Controls.Add(browser);
            Controls.Add(status);
            Controls.Add(actions);
            Shown += async (sender, args) => await InitializeBrowser();
            FormClosing += (sender, args) => { if (checking && !parentClosing) args.Cancel = true; };
            FormClosed += (sender, args) => Emit(new { type = "closed" });
        }

        /// <summary>Инициализирует Evergreen Runtime, ограничивает навигацию и начинает чтение команд родителя.</summary>
        private async Task InitializeBrowser()
        {
            try
            {
                var environment = await CoreWebView2Environment.CreateAsync(null, profile);
                await browser.EnsureCoreWebView2Async(environment);
                if (IsDisposed) return;
                browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
                browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
                browser.CoreWebView2.Settings.IsWebMessageEnabled = false;
                browser.CoreWebView2.Settings.AreHostObjectsAllowed = false;
                browser.CoreWebView2.NavigationStarting += (sender, args) =>
                {
                    if (!IsVkUrl(args.Uri))
                    {
                        args.Cancel = true;
                        status.Text = "Продолжите вход на сайте VK в этом окне";
                    }
                };
                browser.CoreWebView2.NewWindowRequested += (sender, args) =>
                {
                    args.Handled = true;
                    if (IsVkUrl(args.Uri)) browser.CoreWebView2.Navigate(args.Uri);
                };
                browser.CoreWebView2.NavigationCompleted += async (sender, args) =>
                {
                    if (!args.IsSuccess)
                    {
                        status.Text = "Не удалось загрузить сайт VK. Проверьте соединение и обновите страницу";
                        return;
                    }
                    await ReadCookies(false);
                };
                browser.CoreWebView2.SourceChanged += async (sender, args) => await ReadCookies(false);
                browser.CoreWebView2.ProcessFailed += (sender, args) =>
                {
                    Emit(new { type = "error", code = "browser_failed" });
                    parentClosing = true;
                    Close();
                };
                complete.Enabled = true;
                reload.Enabled = true;
                status.Text = "Войдите в аккаунт VK. Подключение завершится автоматически";
                parentReader = Task.Run((Action)ReadParentCommands);
                browser.CoreWebView2.Navigate("https://vk.ru/");
            }
            catch (WebView2RuntimeNotFoundException)
            {
                Emit(new { type = "error", code = "runtime_missing" });
                Close();
            }
            catch
            {
                Emit(new { type = "error", code = "browser_failed" });
                Close();
            }
        }

        /// <summary>Нативно получает HttpOnly cookies; одну пару автоматически отправляет лишь один раз.</summary>
        private async Task ReadCookies(bool manual)
        {
            if (IsDisposed || browser.CoreWebView2 == null || checking || readingCookies || !IsVkUrl(browser.CoreWebView2.Source)) return;
            var page = new Uri(browser.CoreWebView2.Source);
            if (!manual && !(new[] { "vk.ru", "www.vk.ru", "m.vk.ru", "vk.com", "www.vk.com", "m.vk.com" }).Contains(page.Host)) return;
            readingCookies = true;
            try
            {
                var domains = page.Host.EndsWith("vk.com") ? new[] { "vk.com", "vk.ru" } : new[] { "vk.ru", "vk.com" };
                foreach (var domain in domains)
                {
                    var cookies = new List<CoreWebView2Cookie>();
                    foreach (var url in new[] { "https://" + domain + "/", "https://login." + domain + "/", "https://m." + domain + "/" })
                        cookies.AddRange(await browser.CoreWebView2.CookieManager.GetCookiesAsync(url));
                    if (IsDisposed || checking) return;
                    var p = cookies.FirstOrDefault(cookie => cookie.Name == "p" && !String.IsNullOrEmpty(cookie.Value));
                    var sid = cookies.FirstOrDefault(cookie => cookie.Name == "remixsid" && !String.IsNullOrEmpty(cookie.Value));
                    if (p == null || sid == null) continue;
                    if (!manual && attemptedP == p.Value && attemptedSid == sid.Value) return;
                    attemptedP = p.Value;
                    attemptedSid = sid.Value;
                    SetState(true, null);
                    Emit(new { type = "session", p = p.Value, remixsid = sid.Value, userAgent = browser.CoreWebView2.Settings.UserAgent });
                    return;
                }
                if (manual) status.Text = "Сначала войдите в аккаунт на сайте VK";
            }
            catch { if (!IsDisposed) status.Text = "Не удалось получить сессию VK. Нажмите «Я вошёл» для повтора"; }
            finally { readingCookies = false; }
        }

        /// <summary>Читает только сообщения состояния/закрытия; потеря родителя закрывает окно и браузер.</summary>
        private void ReadParentCommands()
        {
            try
            {
                string line;
                var inputJson = new JavaScriptSerializer();
                while ((line = Console.In.ReadLine()) != null)
                {
                    var command = inputJson.Deserialize<Dictionary<string, object>>(line);
                    if (IsDisposed || !IsHandleCreated) return;
                    BeginInvoke((Action)(() => ApplyParentCommand(command)));
                }
            }
            catch { }
            try { if (!IsDisposed && IsHandleCreated) BeginInvoke((Action)(() => { parentClosing = true; Close(); })); }
            catch { }
        }

        /// <summary>Применяет проверку shared-репозитория в UI-потоке, без журналирования текста команд.</summary>
        private void ApplyParentCommand(Dictionary<string, object> command)
        {
            if (IsDisposed) return;
            object type;
            if (!command.TryGetValue("type", out type)) return;
            if ((string)type == "close") { parentClosing = true; Close(); return; }
            if ((string)type != "state") return;
            object busy;
            object error;
            SetState(command.TryGetValue("busy", out busy) && busy is bool && (bool)busy,
                command.TryGetValue("error", out error) ? error as string : null);
        }

        /// <summary>Не даёт отправлять повторные cookies во время проверки и показывает безопасный результат.</summary>
        private void SetState(bool busy, string error)
        {
            checking = busy;
            complete.Enabled = !busy;
            reload.Enabled = !busy;
            status.Text = busy ? "Подключаем аккаунт VK…" : error ?? "Войдите в аккаунт VK и нажмите «Я вошёл»";
        }

        /// <summary>Освобождает WebView2 вместе с окном; после входа браузер не нужен для обновления токена.</summary>
        protected override void Dispose(bool disposing)
        {
            if (disposing) browser.Dispose();
            base.Dispose(disposing);
        }
    }
}
