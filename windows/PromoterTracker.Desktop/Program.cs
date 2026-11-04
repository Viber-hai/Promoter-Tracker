using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
namespace PromoterTracker.Desktop;
internal static class Program
{
 [STAThread] private static void Main(){ApplicationConfiguration.Initialize();Application.Run(new MainForm());}
}
internal sealed class MainForm : Form
{
 private readonly WebView2 browser=new(){Dock=DockStyle.Fill};
 public MainForm()
 {
  Text="Promoter Tracker";StartPosition=FormStartPosition.CenterScreen;MinimumSize=new Size(980,700);
  Size=new Size(1280,850);BackColor=Color.FromArgb(15,20,36);Controls.Add(browser);
  Shown+=async (_,_)=>await LoadTrackerAsync();
 }
 private async Task LoadTrackerAsync()
 {
  try
  {
   var dataDirectory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PromoterTracker","WebView2");
   Directory.CreateDirectory(dataDirectory);
   var environment=await CoreWebView2Environment.CreateAsync(null,dataDirectory);
   await browser.EnsureCoreWebView2Async(environment);
   browser.CoreWebView2.Settings.IsStatusBarEnabled=false;
   browser.CoreWebView2.Settings.IsZoomControlEnabled=true;
   browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled=true;
   var page=Path.Combine(AppContext.BaseDirectory,"wwwroot","index.html");
   if(!File.Exists(page))throw new FileNotFoundException("The tracker page is missing.",page);
   browser.Source=new Uri(page);
  }
  catch(Exception ex)
  {
   MessageBox.Show(this,"Promoter Tracker could not start. Make sure the Microsoft Edge WebView2 Runtime is installed.\n\n"+ex.Message,
    "Promoter Tracker",MessageBoxButtons.OK,MessageBoxIcon.Error);Close();
  }
 }
}