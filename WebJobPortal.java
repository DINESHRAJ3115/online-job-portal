/*
 * CareerConnect - Online Job Portal as a WEBSITE written entirely in Java.
 * Uses only the JDK's built-in HTTP server (no Maven, Spring or other libraries).
 *
 * Run (JDK 11+):   java WebJobPortal.java          -> then open http://localhost:8080
 * or compile:      javac WebJobPortal.java && java WebJobPortal [port]
 *
 * NOTE: the main class must stay first in this file for "java WebJobPortal.java" to work.
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/* ============================ WEB LAYER ============================ */

public class WebJobPortal {
    static final JobPortalService svc = new JobPortalService();
    static final String[] CITIES = {"Chennai", "Bengaluru", "Tiruchirappalli", "Coimbatore", "Madurai"};
    static final String[] DOMAINS = {"Software Dev", "Artificial Intelligence", "FinTech", "Cloud/DevOps", "Cybersecurity"};
    static final String[] QUALS = {"B.E. CSE (AI & ML)", "B.E. CSE", "B.Tech IT", "B.E. ECE", "M.E. / M.Tech", "MCA"};
    static final String[] AVAIL = {"Immediate / Within 15 Days", "30 Days Notice", "60 Days Notice", "90 Days Notice"};

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WebJobPortal::handle);
        server.start();
        System.out.println("CareerConnect is running at http://localhost:" + port + "  (Ctrl+C to stop)");
    }

    static synchronized void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
            Map<String, String> f = "POST".equals(method)
                    ? parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    : new HashMap<>();
            if ("/switch".equals(path) && "POST".equals(method)) {
                svc.switchSeeker(f.get("seeker"));
                ex.getResponseHeaders().add("Location", "/");
                ex.sendResponseHeaders(303, -1);
                ex.close();
                return;
            }
            String html = route(method, path, q, f);
            send(ex, html == null ? 404 : 200, html == null ? layout("", "<div class='panel'>Page not found.</div>", null) : html);
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "<h1>Server error</h1><pre>" + esc(String.valueOf(e)) + "</pre>");
        }
    }

    static String route(String method, String path, Map<String, String> q, Map<String, String> f) {
        boolean post = "POST".equals(method);
        switch (path) {
            case "/":
            case "/register": {
                String[] msg = null;
                if (post) {
                    try {
                        JobSeeker s = svc.registerJobSeeker(f.get("name"), f.get("email"), f.get("phone"), f.get("pass"),
                                f.get("qual"), parseInt(f.get("exp")), f.get("city"), f.get("skills"), f.get("resume"));
                        msg = new String[]{"Registered. Your ID is " + s.id + ".", "ok"};
                        f = new HashMap<>();
                    } catch (PortalValidationException e) {
                        msg = new String[]{e.getMessage(), "err"};
                    }
                }
                return registerPage(f, msg);
            }
            case "/search":
                return searchPage(q);
            case "/apply": {
                if (!post) return applyPage(q.get("job"), null);
                try {
                    JobApplication a = svc.submitApplication(svc.findVacancy(f.get("job")), svc.current(),
                            f.get("cover"), f.get("avail"));
                    return trackerPage(new String[]{"Application " + a.id + " submitted.", "ok"});
                } catch (DuplicateApplicationException | PortalValidationException e) {
                    return applyPage(f.get("job"), new String[]{e.getMessage(), "err"});
                }
            }
            case "/tracker":
                return trackerPage(null);
            case "/advance": {
                if (!post) return trackerPage(null);
                JobApplication a = svc.findApplication(f.get("app"));
                if (a == null) return trackerPage(new String[]{"Application not found.", "err"});
                svc.advanceStatus(a);
                return trackerPage(new String[]{a.id + " is now: " + ApplicationStatus.values()[a.status].label, "ok"});
            }
            case "/publish": {
                if (!post) return trackerPage(null);
                try {
                    JobVacancy v = svc.publishVacancy(f.get("title"), f.get("company"), f.get("city"), f.get("domain"),
                            f.get("lpa"), f.get("skills"));
                    return trackerPage(new String[]{"Published " + v.id + ". It now appears in Search.", "ok"});
                } catch (PortalValidationException e) {
                    return trackerPage(new String[]{e.getMessage(), "err"});
                }
            }
            default:
                return null;
        }
    }

    /* ---------- pages ---------- */

    static String registerPage(Map<String, String> f, String[] msg) {
        StringBuilder b = new StringBuilder("<div class='grid'><div class='panel'><h2>Register as a job seeker</h2>");
        b.append("<form method='post' action='/register'>")
         .append(field("Full name", "name", "text", f))
         .append(field("Email", "email", "email", f))
         .append(field("Mobile (10 digits, starts with 6-9)", "phone", "text", f))
         .append("<label>Password (min 6 characters)<input type='password' name='pass'></label>")
         .append("<label>Qualification<select name='qual'>").append(opts(Arrays.asList(QUALS), val(f, "qual"), null)).append("</select></label>")
         .append("<label>Experience (years)<input type='number' name='exp' min='0' max='25' value='").append(esc(val(f, "exp").isEmpty() ? "0" : val(f, "exp"))).append("'></label>")
         .append("<label>Preferred city<select name='city'>").append(opts(Arrays.asList(CITIES), val(f, "city"), null)).append("</select></label>")
         .append(field("Technical skills (comma separated)", "skills", "text", f))
         .append("<label>Resume summary<textarea name='resume' rows='3'>").append(esc(val(f, "resume"))).append("</textarea></label>")
         .append("<button class='btn'>Register and activate profile</button></form></div>");
        JobSeeker me = svc.current();
        b.append("<div class='panel'><h2>Active profile and best matches</h2><pre>");
        if (me == null) b.append("No candidate registered.");
        else {
            b.append(esc(String.format("Candidate ID  : %s%nName          : %s%nEmail         : %s%nRole          : %s%nExperience    : %d yr(s)%nSkills        : %s%n%nTop matching vacancies:%n",
                    me.id, me.name, me.email, me.roleTitle(), me.exp, String.join(", ", me.skills))));
            svc.vacancies().stream()
                    .sorted(Comparator.comparingDouble((JobVacancy v) -> -svc.match(v, me))).limit(4)
                    .forEach(v -> b.append(esc(String.format(" * [%s] %s (%s) -> %.1f%%%n", v.id, v.title, v.company, svc.match(v, me)))));
        }
        b.append("</pre></div></div>");
        return layout("register", b.toString(), msg);
    }

    static String searchPage(Map<String, String> q) {
        String kw = val(q, "kw"), city = val(q, "city"), dom = val(q, "domain");
        int maxExp = q.containsKey("exp") ? parseInt(q.get("exp")) : 5;
        double minLpa = q.containsKey("lpa") ? parseDouble(q.get("lpa")) : 0;
        List<JobVacancy> rows = svc.search(kw, city.isEmpty() ? "All Locations" : city,
                dom.isEmpty() ? "All Categories" : dom, maxExp, minLpa);
        String base = "kw=" + enc(kw) + "&city=" + enc(city) + "&domain=" + enc(dom) + "&exp=" + maxExp + "&lpa=" + minLpa;
        StringBuilder b = new StringBuilder("<div class='panel'><h2>Find vacancies</h2><form method='get' action='/search' class='filters'>");
        b.append("<label>Keyword<input name='kw' value='").append(esc(kw)).append("' placeholder='Role, company or skill'></label>")
         .append("<label>City<select name='city'>").append(opts(Arrays.asList(CITIES), city, "All Locations")).append("</select></label>")
         .append("<label>Domain<select name='domain'>").append(opts(Arrays.asList(DOMAINS), dom, "All Categories")).append("</select></label>")
         .append("<label>Max exp (yrs)<input type='number' name='exp' min='0' max='20' value='").append(maxExp).append("'></label>")
         .append("<label>Min CTC (LPA)<input type='number' name='lpa' min='0' max='30' step='0.5' value='").append(minLpa).append("'></label>")
         .append("<button class='btn'>Apply filters</button></form></div>");
        b.append("<div class='tw'><table><tr><th>Job ID</th><th>Role</th><th>Company</th><th>City</th><th>Domain</th><th>Min exp</th><th>CTC</th><th>Match</th></tr>");
        JobSeeker me = svc.current();
        if (rows.isEmpty()) b.append("<tr><td colspan='8' class='empty'>No vacancies match these filters. Widen the city, domain or experience limit.</td></tr>");
        for (JobVacancy v : rows) {
            double m = svc.match(v, me);
            boolean sel = v.id.equals(q.get("sel"));
            b.append("<tr class='").append(sel ? "sel" : "").append("'><td><a href='/search?").append(base).append("&sel=").append(v.id).append("'>")
             .append(v.id).append("</a></td><td>").append(esc(v.title)).append("</td><td>").append(esc(v.company)).append("</td><td>")
             .append(esc(v.location)).append("</td><td>").append(esc(v.category)).append("</td><td>").append(v.minExp)
             .append(" Yrs</td><td>").append(String.format("%.1f LPA", v.lpa)).append("</td><td class='").append(cls(m)).append("'>")
             .append(String.format("%.1f%%", m)).append("</td></tr>");
        }
        b.append("</table></div><div class='panel' style='margin-top:16px'><h2>Selected vacancy</h2>");
        JobVacancy s = q.get("sel") == null ? null : svc.findVacancy(q.get("sel"));
        if (s == null) b.append("<p>Click a Job ID to see details and your skill match.</p>");
        else {
            double m = svc.match(s, me);
            b.append("<pre>").append(esc(String.format("%s | %s | %s (%s)%nRequired skills: %s%nYour match: %.1f%% (%s)%nPackage: %.1f LPA | Minimum experience: %d yr(s)%n%s",
                    s.id, s.title, s.company, s.location, String.join(", ", s.skills), m, fit(m), s.lpa, s.minExp, s.desc))).append("</pre>")
             .append("<a class='btn' href='/apply?job=").append(s.id).append("'>Apply for this vacancy</a>");
        }
        return layout("search", b.append("</div>").toString(), null);
    }

    static String applyPage(String jobId, String[] msg) {
        JobVacancy v = jobId == null ? null : svc.findVacancy(jobId);
        if (v == null) v = svc.vacancies().get(0);
        StringBuilder b = new StringBuilder("<div class='panel narrow'><h2>Submit application</h2>");
        b.append("<form method='get' action='/apply'><label>Vacancy<select name='job' onchange='this.form.submit()'>");
        for (JobVacancy x : svc.vacancies())
            b.append("<option value='").append(x.id).append("'").append(x.id.equals(v.id) ? " selected" : "").append(">")
             .append(esc(x.id + " - " + x.title + " (" + x.company + " | " + x.location + ")")).append("</option>");
        b.append("</select></label><noscript><button class='btn'>Select</button></noscript></form>");
        double m = svc.match(v, svc.current());
        b.append("<div class='banner'>Compatibility: ").append(String.format("%.1f%%", m)).append(" (").append(fit(m))
         .append(") | Required skills: ").append(esc(String.join(", ", v.skills))).append("</div>")
         .append("<form method='post' action='/apply'><input type='hidden' name='job' value='").append(v.id).append("'>")
         .append("<label>Joining availability<select name='avail'>").append(opts(Arrays.asList(AVAIL), null, null)).append("</select></label>")
         .append("<label>Statement of interest (min 20 characters)<textarea name='cover' rows='6'></textarea></label>")
         .append("<button class='btn'>Submit application</button></form></div>");
        return layout("apply", b.toString(), msg);
    }

    static String trackerPage(String[] msg) {
        JobSeeker me = svc.current();
        StringBuilder b = new StringBuilder("<div class='kpis'>");
        b.append(kpi(svc.vacancies().size(), "Active vacancies")).append(kpi(svc.seekerCount(), "Registered job seekers"))
         .append(kpi(svc.applications().size(), "Submitted applications")).append("</div><div class='grid'>");
        b.append("<div class='panel'><h2>Your applications</h2><div class='tw'><table><tr><th>App ID</th><th>Job</th><th>Role</th><th>Match</th><th>Status</th><th></th></tr>");
        int n = 0;
        for (JobApplication a : svc.applications()) {
            if (me == null || !a.seekerId.equals(me.id)) continue;
            n++;
            b.append("<tr><td>").append(a.id).append("</td><td>").append(a.jobId).append("</td><td>").append(esc(a.title)).append("</td><td>")
             .append(String.format("%.1f%%", a.match)).append("</td><td>").append(ApplicationStatus.values()[a.status].label)
             .append("</td><td><form method='post' action='/advance'><input type='hidden' name='app' value='").append(a.id)
             .append("'><button class='btn small'>Advance status</button></form></td></tr>");
        }
        if (n == 0) b.append("<tr><td colspan='6' class='empty'>No applications yet. Pick a vacancy in Search and apply.</td></tr>");
        b.append("</table></div><p class='hint'>\"Advance status\" simulates a recruiter moving the application to the next stage.</p></div>");
        b.append("<div class='panel'><h2>Recruiter desk: post a vacancy</h2><form method='post' action='/publish'>")
         .append("<label>Role title<input name='title'></label><label>Company<input name='company'></label>")
         .append("<label>City<select name='city'>").append(opts(Arrays.asList(CITIES), null, null)).append("</select></label>")
         .append("<label>Domain<select name='domain'>").append(opts(Arrays.asList(DOMAINS), null, null)).append("</select></label>")
         .append("<label>CTC (LPA)<input type='number' name='lpa' min='0.5' step='0.5'></label>")
         .append("<label>Required skills (comma separated)<input name='skills' placeholder='Java, Python, OpenCV'></label>")
         .append("<button class='btn'>Publish vacancy</button></form></div></div>");
        return layout("tracker", b.toString(), msg);
    }

    /* ---------- shared HTML helpers ---------- */

    static String layout(String active, String body, String[] msg) {
        String[][] tabs = {{"register", "/", "1. Registration & Profile"}, {"search", "/search", "2. Search & Filter"},
                {"apply", "/apply", "3. Apply for Vacancy"}, {"tracker", "/tracker", "4. Tracker & Recruiter Desk"}};
        StringBuilder b = new StringBuilder("<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1'><title>CareerConnect - Online Job Portal</title><style>")
                .append(CSS).append("</style></head><body><header><div><h1>CareerConnect: Online Job Portal</h1>")
                .append("<small>SRM TRP Engineering College | CS25C09 Java Programming PBL | UN SDG 8</small></div>")
                .append("<form method='post' action='/switch' class='who'><span>Active candidate:</span><select name='seeker' onchange='this.form.submit()'>");
        for (JobSeeker s : svc.seekers())
            b.append("<option value='").append(s.id).append("'").append(svc.current() == s ? " selected" : "").append(">")
             .append(esc(s.name + " [" + s.id + "]")).append("</option>");
        b.append("</select><noscript><button>Go</button></noscript></form></header><nav>");
        for (String[] t : tabs)
            b.append("<a href='").append(t[1]).append("'").append(t[0].equals(active) ? " class='on'" : "").append(">").append(esc(t[2])).append("</a>");
        b.append("</nav><main>");
        if (msg != null) b.append("<div class='msg ").append(msg[1]).append("' role='status'>").append(esc(msg[0])).append("</div>");
        return b.append(body).append("</main><footer>Java web version | skill match = matched required skills / total required skills</footer></body></html>").toString();
    }

    static final String CSS = ":root{--ink:#14284b;--ink2:#32476b;--bg:#f4f6fa;--line:#d8dfeb;--accent:#0a7c86;--ok:#176b3a;--bad:#b3261e}"
            + "*{box-sizing:border-box}body{margin:0;font:15px/1.5 'Segoe UI',system-ui,sans-serif;background:var(--bg);color:#1c2433}"
            + "header{background:var(--ink);color:#fff;padding:16px 24px;display:flex;flex-wrap:wrap;gap:12px;justify-content:space-between;align-items:center}"
            + "h1{margin:0;font:700 21px Georgia,serif}small{display:block;color:#b9c6df;font-size:13px}.who{color:#fef3b0;font-size:13px;display:flex;gap:8px;align-items:center}"
            + "nav{display:flex;flex-wrap:wrap;gap:4px;padding:10px 24px 0;background:#e6ebf3;border-bottom:1px solid var(--line)}"
            + "nav a{padding:10px 16px;color:var(--ink2);text-decoration:none;font-weight:600;border-radius:6px 6px 0 0}nav a.on{background:var(--bg);color:var(--ink);border:1px solid var(--line);border-bottom:0}"
            + "main{max-width:1100px;margin:0 auto;padding:22px 24px 50px}.grid{display:grid;gap:20px;grid-template-columns:1fr 1fr}@media(max-width:800px){.grid{grid-template-columns:1fr}}"
            + ".panel{background:#fff;border:1px solid var(--line);border-radius:8px;padding:18px}.narrow{max-width:720px}h2{margin:0 0 12px;font-size:17px;color:var(--ink)}"
            + "label{display:block;font-weight:600;font-size:13px;margin:10px 0 3px;color:var(--ink2)}input,select,textarea{width:100%;padding:8px 10px;border:1px solid #b7c2d6;border-radius:5px;font:inherit;background:#fff}"
            + ".btn{display:inline-block;margin-top:14px;background:var(--accent);color:#fff;border:0;padding:10px 18px;border-radius:5px;font:600 14px inherit;cursor:pointer;text-decoration:none}.btn.small{margin:0;padding:5px 10px;font-size:12px}"
            + ".filters{display:grid;grid-template-columns:2fr 1.2fr 1.4fr .8fr 1fr;gap:10px;align-items:end}@media(max-width:800px){.filters{grid-template-columns:1fr 1fr}}"
            + ".tw{overflow-x:auto;margin-top:14px;border:1px solid var(--line);border-radius:8px;background:#fff}table{border-collapse:collapse;width:100%;font-size:14px}"
            + "th{background:var(--ink);color:#fff;text-align:left;padding:9px 10px;white-space:nowrap}td{padding:9px 10px;border-top:1px solid #e8edf5;white-space:nowrap}tr.sel{background:#d6eef0}"
            + ".hi{color:var(--ok);font-weight:700}.mid{color:#8a5a00;font-weight:700}.lo{color:var(--bad);font-weight:700}pre{margin:0;font:13px/1.55 Consolas,monospace;white-space:pre-wrap}"
            + ".banner{background:#e3f4ea;color:var(--ok);padding:10px 12px;border-radius:5px;font-weight:600;margin-top:12px}.msg{margin-bottom:16px;padding:10px 14px;border-radius:5px}"
            + ".msg.err{background:#fde7e5;color:var(--bad)}.msg.ok{background:#e3f4ea;color:var(--ok)}.kpis{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:20px}"
            + ".kpi{background:#fff;border:1px solid var(--line);border-radius:8px;padding:14px;text-align:center;color:var(--ink2)}.kpi b{display:block;font-size:26px;color:var(--ink)}"
            + ".empty,.hint{color:#5b6b80;text-align:center}footer{text-align:center;color:#5b6b80;font-size:12.5px;padding:14px}";

    static String kpi(int n, String label) { return "<div class='kpi'><b>" + n + "</b>" + label + "</div>"; }
    static String field(String label, String name, String type, Map<String, String> f) {
        return "<label>" + label + "<input type='" + type + "' name='" + name + "' value='" + esc(val(f, name)) + "'></label>";
    }
    static String opts(List<String> items, String selected, String first) {
        StringBuilder b = new StringBuilder();
        List<String> all = new ArrayList<>();
        if (first != null) all.add(first);
        all.addAll(items);
        for (String s : all)
            b.append("<option").append(s.equals(selected) ? " selected" : "").append(">").append(esc(s)).append("</option>");
        return b.toString();
    }
    static String cls(double m) { return m >= 70 ? "hi" : m >= 40 ? "mid" : "lo"; }
    static String fit(double m) { return m >= 70 ? "Strong fit" : m >= 40 ? "Partial fit" : "Low fit"; }
    static String val(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }
    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
    static int parseInt(String s) { try { return Math.max(0, Integer.parseInt(s.trim())); } catch (Exception e) { return 0; } }
    static double parseDouble(String s) { try { return Math.max(0, Double.parseDouble(s.trim())); } catch (Exception e) { return 0; } }
    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String p : s.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i), v = i < 0 ? "" : p.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}

/* ============================ MODEL (OOP) ============================ */

abstract class PortalUser {
    final String id, name, email, phone, pass;
    PortalUser(String id, String name, String email, String phone, String pass) {
        this.id = id; this.name = name; this.email = email; this.phone = phone; this.pass = pass;
    }
    abstract String roleTitle();
}

class JobSeeker extends PortalUser {
    final String qual, city, resume;
    final int exp;
    final List<String> skills;
    JobSeeker(String id, String name, String email, String phone, String pass, String qual, int exp,
              String city, List<String> skills, String resume) {
        super(id, name, email, phone, pass);
        this.qual = qual; this.exp = exp; this.city = city; this.skills = new ArrayList<>(skills); this.resume = resume;
    }
    @Override String roleTitle() { return "Job Seeker (" + qual + ")"; }
}

class JobVacancy {
    final String id, title, company, location, category, desc;
    final int minExp;
    final double lpa;
    final List<String> skills;
    JobVacancy(String id, String title, String company, String location, String category, int minExp,
               double lpa, List<String> skills, String desc) {
        this.id = id; this.title = title; this.company = company; this.location = location; this.category = category;
        this.minExp = minExp; this.lpa = lpa; this.skills = new ArrayList<>(skills); this.desc = desc;
    }
}

enum ApplicationStatus {
    SUBMITTED("Submitted - Under Review"), SHORTLISTED("Shortlisted for Technical Round"),
    INTERVIEW_SCHEDULED("Interview Scheduled"), OFFERED("Offer Extended"), REJECTED("Not Selected");
    final String label;
    ApplicationStatus(String label) { this.label = label; }
}

class JobApplication {
    final String id, jobId, title, company, seekerId;
    final double match;
    int status; // index into ApplicationStatus.values()
    JobApplication(String id, JobVacancy v, JobSeeker s, double match) {
        this.id = id; this.jobId = v.id; this.title = v.title; this.company = v.company; this.seekerId = s.id; this.match = match;
    }
}

class PortalValidationException extends Exception {
    PortalValidationException(String m) { super(m); }
}

class DuplicateApplicationException extends Exception {
    DuplicateApplicationException(String m) { super(m); }
}

/* ============================ SERVICE LAYER ============================ */

class JobPortalService {
    private final Map<String, JobSeeker> seekersByEmail = new LinkedHashMap<>();
    private final List<JobVacancy> vacancies = new ArrayList<>();
    private final List<JobApplication> applications = new ArrayList<>();
    private JobSeeker current;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");
    private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");

    JobPortalService() { seed(); }

    static List<String> csv(String s) {
        return Arrays.stream((s == null ? "" : s).split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private void vac(String id, String t, String co, String loc, String cat, int exp, double lpa, String sk, String d) {
        vacancies.add(new JobVacancy(id, t, co, loc, cat, exp, lpa, csv(sk), d));
    }

    private void seed() {
        JobSeeker a = new JobSeeker("JS-1001", "Aravind Krishnan", "aravind.aiml@srmtrp.edu.in", "9876543210", "aravind123",
                "B.E. CSE (AI & ML)", 1, "Chennai", csv("Java, OOP, Python, ML, SQL, Swing, Git"), "");
        JobSeeker d = new JobSeeker("JS-1002", "Divya Lakshmi", "divya.cse@srmtrp.edu.in", "9790012345", "divya123",
                "B.E. CSE", 0, "Tiruchirappalli", csv("Python, SQL, Data Structures"), "");
        seekersByEmail.put(a.email, a);
        seekersByEmail.put(d.email, d);
        current = a;
        vac("JOB-201", "Junior Java Software Engineer", "Zoho Corporation", "Chennai", "Software Dev", 0, 8.5, "Java, OOP, SQL, Data Structures, Git", "Design, build and maintain high-performance Java modules for enterprise SaaS applications.");
        vac("JOB-202", "AI & ML Associate", "TCS Research", "Bengaluru", "Artificial Intelligence", 1, 9.2, "Python, ML, SQL, Statistics, Git", "Develop and evaluate machine learning models for research prototypes.");
        vac("JOB-203", "Full-Stack Developer (Java+React)", "Freshworks Inc.", "Chennai", "Software Dev", 1, 11.0, "Java, React, SQL, REST, Git", "Build customer-facing features with Java back-ends and React front-ends.");
        vac("JOB-204", "Graduate Engineer Trainee", "PayPal India", "Chennai", "FinTech", 0, 14.5, "Java, SQL, Data Structures, Algorithms, Python", "Join the payments engineering rotation program.");
        vac("JOB-205", "Data Science & NLP Intern", "IITM Incubation", "Tiruchirappalli", "Artificial Intelligence", 0, 4.8, "Python, ML, NLP, SQL, Git", "Stipended research internship on NLP pipelines.");
        vac("JOB-206", "Cloud Support Engineer", "Cognizant Digital", "Coimbatore", "Cloud/DevOps", 1, 6.5, "Linux, AWS, Docker, Git", "Operate and monitor cloud workloads for enterprise customers.");
        vac("JOB-207", "SDE - GUI & Tools", "vdart Digital", "Tiruchirappalli", "Software Dev", 0, 6.2, "Java, Swing, OOP, SQL, Git", "Build internal desktop tools using Java Swing and SQL back-ends.");
        vac("JOB-208", "Security Analyst Trainee", "HCLTech", "Madurai", "Cybersecurity", 0, 5.5, "Networking, Linux, Python, Security", "Monitor alerts and assist with vulnerability assessments in a SOC.");
        applications.add(new JobApplication("APP-5001", findVacancy("JOB-201"), a, match(findVacancy("JOB-201"), a)));
        JobApplication second = new JobApplication("APP-5002", findVacancy("JOB-202"), a, match(findVacancy("JOB-202"), a));
        second.status = ApplicationStatus.INTERVIEW_SCHEDULED.ordinal();
        applications.add(second);
    }

    JobSeeker registerJobSeeker(String name, String email, String phone, String pass, String qual, int exp,
                                String city, String skillsCsv, String resume) throws PortalValidationException {
        if (name == null || name.trim().length() < 3) throw new PortalValidationException("Full name must contain at least 3 characters.");
        if (email == null || !EMAIL.matcher(email.trim()).matches()) throw new PortalValidationException("Enter a valid email address.");
        if (phone == null || !PHONE.matcher(phone.trim()).matches()) throw new PortalValidationException("Mobile must be 10 digits and start with 6, 7, 8 or 9.");
        if (pass == null || pass.length() < 6) throw new PortalValidationException("Password must be at least 6 characters.");
        String norm = email.trim().toLowerCase();
        if (seekersByEmail.containsKey(norm)) throw new PortalValidationException("An account with this email already exists.");
        List<String> skills = csv(skillsCsv);
        if (skills.isEmpty()) throw new PortalValidationException("Add at least one technical skill.");
        JobSeeker s = new JobSeeker("JS-" + (1001 + seekersByEmail.size()), name.trim(), email.trim(), phone.trim(), pass,
                qual, exp, city, skills, resume == null ? "" : resume.trim());
        seekersByEmail.put(norm, s);
        current = s;
        return s;
    }

    /** Match % = round1( |seeker skills intersect required skills| / |required skills| * 100 ). */
    double match(JobVacancy v, JobSeeker s) {
        if (s == null || v.skills.isEmpty()) return 0.0;
        Set<String> have = s.skills.stream().map(String::toLowerCase).collect(Collectors.toSet());
        long hit = v.skills.stream().filter(r -> have.contains(r.toLowerCase())).count();
        return Math.round(hit * 1000.0 / v.skills.size()) / 10.0;
    }

    List<JobVacancy> search(String keyword, String location, String category, int maxExp, double minLpa) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return vacancies.stream()
                .filter(v -> kw.isEmpty() || v.title.toLowerCase().contains(kw) || v.company.toLowerCase().contains(kw)
                        || v.skills.stream().anyMatch(s -> s.toLowerCase().contains(kw)))
                .filter(v -> "All Locations".equals(location) || v.location.equalsIgnoreCase(location))
                .filter(v -> "All Categories".equals(category) || v.category.equalsIgnoreCase(category))
                .filter(v -> v.minExp <= maxExp && v.lpa >= minLpa)
                .collect(Collectors.toList());
    }

    JobApplication submitApplication(JobVacancy job, JobSeeker seeker, String cover, String availability)
            throws DuplicateApplicationException, PortalValidationException {
        if (seeker == null) throw new PortalValidationException("Register or choose a candidate first.");
        if (job == null) throw new PortalValidationException("Select a vacancy.");
        if (cover == null || cover.trim().length() < 20) throw new PortalValidationException("Statement of interest must be at least 20 characters.");
        if (applications.stream().anyMatch(a -> a.jobId.equals(job.id) && a.seekerId.equals(seeker.id)))
            throw new DuplicateApplicationException("You have already applied for " + job.id + ".");
        JobApplication app = new JobApplication("APP-" + (5001 + applications.size()), job, seeker, match(job, seeker));
        applications.add(app);
        return app;
    }

    JobVacancy publishVacancy(String title, String company, String city, String domain, String lpaText, String skillsCsv)
            throws PortalValidationException {
        if (title == null || title.trim().length() < 3) throw new PortalValidationException("Enter a role title of at least 3 characters.");
        if (company == null || company.trim().isEmpty()) throw new PortalValidationException("Enter a company name.");
        double lpa;
        try { lpa = Double.parseDouble(lpaText.trim()); } catch (Exception e) { throw new PortalValidationException("CTC must be a number (LPA)."); }
        if (lpa <= 0) throw new PortalValidationException("CTC must be greater than zero.");
        List<String> skills = csv(skillsCsv);
        if (skills.isEmpty()) throw new PortalValidationException("Add at least one required skill.");
        JobVacancy v = new JobVacancy("JOB-" + (201 + vacancies.size()), title.trim(), company.trim(), city, domain, 0, lpa, skills,
                "Newly published vacancy for " + title.trim() + ".");
        vacancies.add(v);
        return v;
    }

    void advanceStatus(JobApplication a) { a.status = (a.status + 1) % ApplicationStatus.values().length; }

    void switchSeeker(String id) {
        for (JobSeeker s : seekersByEmail.values()) if (s.id.equals(id)) current = s;
    }

    JobVacancy findVacancy(String id) { return vacancies.stream().filter(v -> v.id.equals(id)).findFirst().orElse(null); }
    JobApplication findApplication(String id) { return applications.stream().filter(a -> a.id.equals(id)).findFirst().orElse(null); }
    JobSeeker current() { return current; }
    Collection<JobSeeker> seekers() { return seekersByEmail.values(); }
    List<JobVacancy> vacancies() { return vacancies; }
    List<JobApplication> applications() { return applications; }
    int seekerCount() { return seekersByEmail.size(); }
}
