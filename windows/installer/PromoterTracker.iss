#define MyAppName "Promoter Tracker"
#define MyAppVersion "1.0.0"
[Setup]
AppId={{7D9E5A44-0D7B-4E3B-8B47-9D1D8A9C42E1}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={autopf}\Promoter Tracker
DefaultGroupName={#MyAppName}
OutputDir=..\dist
OutputBaseFilename=PromoterTracker-Setup
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
UninstallDisplayIcon={app}\PromoterTracker.Desktop.exe
SetupLogging=yes
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\tools\MicrosoftEdgeWebView2Setup.exe"; DestDir: "{app}"; Flags: ignoreversion
[Icons]
Name: "{autoprograms}\Promoter Tracker"; Filename: "{app}\PromoterTracker.Desktop.exe"
Name: "{autodesktop}\Promoter Tracker"; Filename: "{app}\PromoterTracker.Desktop.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\MicrosoftEdgeWebView2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft Edge WebView2 Runtime..."; Flags: waituntilterminated
Filename: "{app}\PromoterTracker.Desktop.exe"; Description: "Launch Promoter Tracker"; Flags: postinstall nowait skipifsilent