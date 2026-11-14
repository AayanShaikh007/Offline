package com.ayaanshaikh.offline;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

final class YouTubeSource {
    private static boolean initialized;

    private static synchronized void initialize() {
        if (!initialized) {
            NewPipe.init(new YouTubeDownloader());
            initialized = true;
        }
    }

    OfflineStore.Snapshot snapshot(PlaylistLink link) throws Exception {
        initialize();
        PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, link.url());
        List<OfflineStore.RemoteSong> songs = new ArrayList<>();
        append(songs, info.getRelatedItems());
        var visited = new HashSet<String>();
        for (Page page = info.getNextPage(); Page.isValid(page); ) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Sync interrupted");
            byte[] body = page.getBody();
            String continuation = body == null ? page.getId() : Base64.getEncoder().encodeToString(body);
            if (!visited.add(page.getUrl() + ":" + continuation) || visited.size() > 100) {
                throw new IOException("Playlist pagination did not finish");
            }
            var next = PlaylistInfo.getMoreItems(ServiceList.YouTube, info.getUrl(), page);
            append(songs, next.getItems());
            page = next.getNextPage();
        }
        if (songs.isEmpty() && !info.getErrors().isEmpty()) throw new IOException("Could not read playlist tracks");
        return new OfflineStore.Snapshot(info.getName(), songs);
    }

    private static void append(List<OfflineStore.RemoteSong> songs, List<StreamInfoItem> streams) {
        for (StreamInfoItem item : streams) {
            String id = PlaylistLink.videoId(item.getUrl());
            if (id != null && item.getDuration() > 0) {
                songs.add(new OfflineStore.RemoteSong(id, item.getName(), artist(item), item.getDuration(), id));
            }
        }
    }

    private static String artist(StreamInfoItem item) {
        return item.getUploaderName() == null ? "" : item.getUploaderName();
    }

    String match(OfflineStore.Song song) throws Exception {
        initialize();
        var search = SearchInfo.getInfo(ServiceList.YouTube.getSearchExtractor(
                song.artist() + " " + song.title() + " audio"));
        String best = null;
        int bestScore = 0;
        boolean tied = false;
        for (InfoItem result : search.getRelatedItems()) {
            if (!(result instanceof StreamInfoItem item)) continue;
            String id = PlaylistLink.videoId(item.getUrl());
            if (id == null) continue;
            int score = TrackMatch.score(song.title(), song.artist(), song.seconds(),
                    item.getName(), artist(item), item.getDuration());
            if (score > bestScore) {
                best = id;
                bestScore = score;
                tied = false;
            } else if (score > 0 && score == bestScore && !id.equals(best)) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    String save(String id, OfflineStore store, Runnable checkNetwork, AudioDownload.Progress progress)
            throws Exception {
        store.ensureAudioDir();
        String recovered = store.findPublishedAudio(id);
        if (recovered != null) return recovered;
        initialize();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        List<AudioStream> formats = info.getAudioStreams().stream()
                .filter(audio -> audio.isUrl() && audio.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                        && audio.getFormat() != null
                        && List.of("m4a", "webm").contains(audio.getFormat().getSuffix()))
                .sorted(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed())
                .limit(5).collect(Collectors.toList());
        IOException failure = new IOException("No supported audio stream");
        for (AudioStream audio : formats) {
            checkNetwork.run();
            progress.update(0, -1);
            try {
                File complete = store.file(id + "." + audio.getFormat().getSuffix());
                return AudioDownload.save(AudioDownload.open(audio.getContent()), complete, checkNetwork, progress);
            } catch (IOException e) {
                failure = e;
            }
        }
        throw failure;
    }

    private static final class YouTubeDownloader extends Downloader {
        @Override public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection connection = null;
            try {
                connection = Http.open(request.url());
                connection.setRequestMethod(request.httpMethod());
                for (var header : request.headers().entrySet()) {
                    for (String value : header.getValue()) connection.addRequestProperty(header.getKey(), value);
                }
                if (request.dataToSend() != null) {
                    connection.setDoOutput(true);
                    try (var body = connection.getOutputStream()) { body.write(request.dataToSend()); }
                }
                int status = connection.getResponseCode();
                if (status == 429) throw new ReCaptchaException("YouTube rate limited the request", request.url());
                String body = "HEAD".equals(request.httpMethod()) ? "" : Http.read(
                        status < 400 ? connection.getInputStream() : connection.getErrorStream());
                return new Response(status, connection.getResponseMessage(), connection.getHeaderFields(),
                        body, connection.getURL().toString());
            } catch (UnknownHostException e) {
                URL endpoint = connection == null ? new URL(request.url()) : connection.getURL();
                throw new Http.DnsFailure(endpoint, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }
}
