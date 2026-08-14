Name:           lost-island
Version:        1.2.0
Release:        1%{?dist}
Summary:        A Dynamic Island for your Linux desktop
License:        MIT
URL:            https://github.com/MarcoZorn/lost-island
Source0:        %{url}/archive/refs/tags/v%{version}.tar.gz
BuildArch:      noarch
Requires:       python3 >= 3.11, python3-gobject, python3-cairo, gtk4, gtk4-layer-shell, libadwaita, pulseaudio-utils
Recommends:     NetworkManager, upower, cava

%description
A fluid, always-on-top island at the top of your screen: music with full
controls and album art, volume OSD, battery, notifications and a pomodoro
timer. Native GTK4, event-driven, light on your battery.

%prep
%autosetup -n %{name}-%{version}

%install
install -d %{buildroot}/usr/lib/%{name}
cp -r lostisland %{buildroot}/usr/lib/%{name}/
install -d %{buildroot}%{_bindir}
cat > %{buildroot}%{_bindir}/lost-island <<'EOF'
#!/bin/sh
PYTHONPATH=/usr/lib/lost-island exec python3 -m lostisland "$@"
EOF
chmod 755 %{buildroot}%{_bindir}/lost-island
install -Dm644 data/lost-island.desktop %{buildroot}%{_datadir}/applications/lost-island.desktop
install -Dm644 data/lost-island.svg %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/lost-island.svg
install -Dm644 data/lost-island.service %{buildroot}/usr/lib/systemd/user/lost-island.service

%files
/usr/lib/%{name}
%{_bindir}/lost-island
%{_datadir}/applications/lost-island.desktop
%{_datadir}/icons/hicolor/scalable/apps/lost-island.svg
/usr/lib/systemd/user/lost-island.service
%license LICENSE

%changelog
* Thu Aug 14 2026 Marco Zorn <m@zorn.it> - 1.2.0-1
- Audio-reactive EQ via cava, pill faces, click-to-close, browser demo

* Thu Aug 14 2026 Marco Zorn <m@zorn.it> - 1.1.0-1
- Settings window, panel overlap, quick toggles, bluetooth, weather, system stats

* Thu Aug 14 2026 Marco Zorn <m@zorn.it> - 1.0.0-1
- Initial release
