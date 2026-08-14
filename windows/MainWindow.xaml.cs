using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace LostIsland;

public partial class MainWindow : Window
{
    static readonly Brush ArtFallback = new SolidColorBrush(Color.FromArgb(0x0F, 0xFF, 0xFF, 0xFF));
    static readonly Duration MorphDuration = new(TimeSpan.FromMilliseconds(250));

    readonly DispatcherTimer _clock = new();
    readonly DispatcherTimer _collapse = new() { Interval = TimeSpan.FromMilliseconds(700) };
    MediaService? _media;
    bool _expanded;

    public MainWindow()
    {
        InitializeComponent();
        _clock.Tick += OnClockTick;
        _collapse.Tick += OnCollapseTick;
        var wa = SystemParameters.WorkArea;
        Left = wa.Left + wa.Width / 2;
        Top = wa.Top;
    }

    void OnLoaded(object sender, RoutedEventArgs e)
    {
        UpdateClocks();
        ArmClock();
        _media = new MediaService(Dispatcher);
        _media.Changed += OnMediaChanged;
        ApplyState(animate: false);
        _ = _media.StartAsync();
    }

    // The 6px top gap lives in the Border margin; the window hugs the work area.
    void OnWindowSizeChanged(object sender, SizeChangedEventArgs e)
    {
        var wa = SystemParameters.WorkArea;
        Left = wa.Left + (wa.Width - ActualWidth) / 2;
        Top = wa.Top;
    }

    // -- clock, ticking once per minute -----------------------------------

    // Fire on the minute boundary instead of every second; the delay is
    // recomputed after each tick so the clock never drifts.
    void ArmClock()
    {
        var now = DateTime.Now;
        _clock.Stop();
        _clock.Interval = TimeSpan.FromMilliseconds(60_000 - now.Second * 1000 - now.Millisecond + 50);
        _clock.Start();
    }

    void OnClockTick(object? sender, EventArgs e)
    {
        UpdateClocks();
        ArmClock();
        Remeasure(animate: true);
    }

    void UpdateClocks()
    {
        var now = DateTime.Now;
        PillClock.Text = now.ToString("HH:mm");
        BigClock.Text = now.ToString("HH:mm");
        BigDate.Text = now.ToString("dddd d MMMM");
    }

    // -- expand / collapse -------------------------------------------------

    void OnIslandClick(object sender, MouseButtonEventArgs e)
    {
        if (!_expanded)
            Expand();
    }

    void OnIslandMouseEnter(object sender, MouseEventArgs e) => _collapse.Stop();

    void OnIslandMouseLeave(object sender, MouseEventArgs e)
    {
        if (!_expanded)
            return;
        _collapse.Stop();
        _collapse.Start();
    }

    void OnCollapseTick(object? sender, EventArgs e)
    {
        _collapse.Stop();
        if (_expanded)
            Collapse();
    }

    void Expand()
    {
        _expanded = true;
        _collapse.Stop();
        ApplyState(animate: true);
    }

    void Collapse()
    {
        _expanded = false;
        _collapse.Stop();
        ApplyState(animate: true);
    }

    void ApplyState(bool animate)
    {
        UpdatePillFace();
        UpdateExpandedFace();
        PillRoot.Visibility = _expanded ? Visibility.Collapsed : Visibility.Visible;
        ExpandedRoot.Visibility = _expanded ? Visibility.Visible : Visibility.Collapsed;
        Remeasure(animate);
    }

    void Remeasure(bool animate)
    {
        ContentHost.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        double w = ContentHost.DesiredSize.Width + 2;   // 1px stroke each side
        double h = ContentHost.DesiredSize.Height + 2;

        // Border renders oversize radii poorly, so the pill radius is capped
        // at half its height — still the flagship's 26px capsule in spirit.
        Island.CornerRadius = new CornerRadius(_expanded ? 34 : Math.Min(26, h / 2));

        if (!animate)
        {
            Island.BeginAnimation(FrameworkElement.WidthProperty, null);
            Island.BeginAnimation(FrameworkElement.HeightProperty, null);
            Island.Width = w;
            Island.Height = h;
            return;
        }

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        Island.BeginAnimation(FrameworkElement.WidthProperty,
            new DoubleAnimation(w, MorphDuration) { EasingFunction = ease });
        Island.BeginAnimation(FrameworkElement.HeightProperty,
            new DoubleAnimation(h, MorphDuration) { EasingFunction = ease });
    }

    // -- faces -------------------------------------------------------------

    void UpdatePillFace()
    {
        bool media = _media is { IsPlaying: true, Title.Length: > 0 };
        PillClock.Visibility = media ? Visibility.Collapsed : Visibility.Visible;
        PillDot.Visibility = media ? Visibility.Visible : Visibility.Collapsed;
        PillTrack.Visibility = PillDot.Visibility;
        if (media)
            PillTrack.Text = _media!.Artist.Length > 0
                ? $"{_media.Artist} — {_media.Title}"
                : _media.Title;
    }

    void UpdateExpandedFace()
    {
        bool media = _media is { HasSession: true, Title.Length: > 0 };
        MediaPanel.Visibility = media ? Visibility.Visible : Visibility.Collapsed;
        ClockPanel.Visibility = media ? Visibility.Collapsed : Visibility.Visible;
        if (!media)
            return;
        TrackTitle.Text = _media!.Title;
        TrackArtist.Text = _media.Artist;
        TrackArtist.Visibility = _media.Artist.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        BtnPlay.Content = _media.IsPlaying ? "\uE769" : "\uE768";
        ArtFrame.Background = _media.Thumbnail is { } art
            ? new ImageBrush(art) { Stretch = Stretch.UniformToFill }
            : ArtFallback;
    }

    void OnMediaChanged()
    {
        UpdatePillFace();
        UpdateExpandedFace();
        Remeasure(animate: true);
    }

    // -- transport ---------------------------------------------------------

    void OnPrev(object sender, RoutedEventArgs e)
    {
        if (_media != null) _ = _media.TrySkipPreviousAsync();
    }

    void OnPlayPause(object sender, RoutedEventArgs e)
    {
        if (_media != null) _ = _media.TryPlayPauseAsync();
    }

    void OnNext(object sender, RoutedEventArgs e)
    {
        if (_media != null) _ = _media.TrySkipNextAsync();
    }
}
