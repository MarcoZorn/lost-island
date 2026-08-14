using System.IO;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace LostIsland;

// Wraps the Global System Media Transport Controls — the same source that
// feeds the Windows volume flyout, so anything with media keys shows up.
public sealed class MediaService
{
    readonly Dispatcher _ui;
    GlobalSystemMediaTransportControlsSessionManager? _manager;
    GlobalSystemMediaTransportControlsSession? _session;
    int _refresh;   // staleness guard for overlapping async refreshes

    public string Title { get; private set; } = "";
    public string Artist { get; private set; } = "";
    public bool IsPlaying { get; private set; }
    public bool HasSession { get; private set; }
    public BitmapImage? Thumbnail { get; private set; }

    public event Action? Changed;

    public MediaService(Dispatcher ui) => _ui = ui;

    public async Task StartAsync()
    {
        try
        {
            _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _manager.CurrentSessionChanged += (_, _) => _ui.InvokeAsync(AttachSession);
            AttachSession();
        }
        catch
        {
            // GSMTC unavailable — the island stays on its clock face.
        }
    }

    void AttachSession()
    {
        if (_session != null)
        {
            _session.MediaPropertiesChanged -= OnMediaPropertiesChanged;
            _session.PlaybackInfoChanged -= OnPlaybackInfoChanged;
        }
        _session = _manager?.GetCurrentSession();
        if (_session != null)
        {
            _session.MediaPropertiesChanged += OnMediaPropertiesChanged;
            _session.PlaybackInfoChanged += OnPlaybackInfoChanged;
        }
        _ = RefreshAsync();
    }

    // WinRT raises these on worker threads; hop to the UI thread first.
    void OnMediaPropertiesChanged(GlobalSystemMediaTransportControlsSession sender, MediaPropertiesChangedEventArgs args)
        => _ui.InvokeAsync(() => _ = RefreshAsync());

    void OnPlaybackInfoChanged(GlobalSystemMediaTransportControlsSession sender, PlaybackInfoChangedEventArgs args)
        => _ui.InvokeAsync(() => _ = RefreshAsync());

    async Task RefreshAsync()
    {
        int seq = ++_refresh;
        var session = _session;
        if (session == null)
        {
            HasSession = false;
            Title = "";
            Artist = "";
            IsPlaying = false;
            Thumbnail = null;
            Changed?.Invoke();
            return;
        }

        string title = "", artist = "";
        bool playing = false;
        BitmapImage? thumb = null;
        try
        {
            playing = session.GetPlaybackInfo().PlaybackStatus
                == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var props = await session.TryGetMediaPropertiesAsync();
            title = props.Title ?? "";
            artist = props.Artist ?? "";
            thumb = await LoadThumbnailAsync(props.Thumbnail);
        }
        catch
        {
            // Session vanished mid-read; publish what we have.
        }

        if (seq != _refresh)
            return;     // a newer refresh already ran
        HasSession = true;
        Title = title;
        Artist = artist;
        IsPlaying = playing;
        Thumbnail = thumb;
        Changed?.Invoke();
    }

    static async Task<BitmapImage?> LoadThumbnailAsync(IRandomAccessStreamReference? reference)
    {
        if (reference == null)
            return null;
        try
        {
            using var winrtStream = await reference.OpenReadAsync();
            using var stream = winrtStream.AsStreamForRead();
            var buffer = new MemoryStream();
            await stream.CopyToAsync(buffer);
            buffer.Position = 0;
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = buffer;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }

    public async Task TryPlayPauseAsync()
    {
        var session = _session;
        if (session == null)
            return;
        try { await session.TryTogglePlayPauseAsync(); } catch { }
    }

    public async Task TrySkipNextAsync()
    {
        var session = _session;
        if (session == null)
            return;
        try { await session.TrySkipNextAsync(); } catch { }
    }

    public async Task TrySkipPreviousAsync()
    {
        var session = _session;
        if (session == null)
            return;
        try { await session.TrySkipPreviousAsync(); } catch { }
    }
}
