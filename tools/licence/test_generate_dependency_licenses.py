import csv, os, subprocess, sys, tempfile, textwrap, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "generate_dependency_licenses.py")

DEPS = textwrap.dedent("""\
    releaseRuntimeClasspath - Runtime classpath of compilation 'release'.
    +--- project :core
    +--- com.itextpdf:itext7-core:7.2.5
    |    \\--- com.itextpdf:kernel:7.2.5 (*)
    +--- androidx.core:core-ktx:1.12.0 -> 1.13.1
    |    \\--- org.jetbrains.kotlin:kotlin-stdlib:1.9.22 (c)
    \\--- com.example:no-license:1.0
    (*) - dependencies omitted (listed previously)
""")

def pom(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(body)

class T(unittest.TestCase):
    def test_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            deps = os.path.join(d, "deps.txt"); open(deps, "w").write(DEPS)
            cache = os.path.join(d, "cache")
            ns = 'xmlns="http://maven.apache.org/POM/4.0.0"'
            pom(f"{cache}/com.itextpdf/itext7-core/7.2.5/h/itext7-core-7.2.5.pom",
                f"<project {ns}><licenses><license><name>GNU Affero General Public License v3</name><url>https://x/agpl</url></license></licenses></project>")
            pom(f"{cache}/com.itextpdf/kernel/7.2.5/h/kernel-7.2.5.pom",
                f"<project {ns}><parent><groupId>com.itextpdf</groupId><artifactId>root</artifactId><version>7.2.5</version></parent></project>")
            pom(f"{cache}/com.itextpdf/root/7.2.5/h/root-7.2.5.pom",
                f"<project {ns}><licenses><license><name>AGPL</name></license></licenses></project>")
            pom(f"{cache}/androidx.core/core-ktx/1.13.1/h/core-ktx-1.13.1.pom",
                f"<project {ns}><licenses><license><name>The Apache Software License, Version 2.0</name></license></licenses></project>")
            pom(f"{cache}/com.example/no-license/1.0/h/no-license-1.0.pom", f"<project {ns}></project>")
            out = os.path.join(d, "out.csv")
            r = subprocess.run([sys.executable, SCRIPT, "--deps", deps, "--cache", cache, "--out", out], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            rows = {(x["group"], x["artifact"]): x for x in csv.DictReader(open(out))}
            self.assertNotIn(("org.jetbrains.kotlin", "kotlin-stdlib"), rows)       # constraint line skipped
            self.assertNotIn(("project", ":core"), rows)
            self.assertEqual(rows[("androidx.core", "core-ktx")]["resolved_version"], "1.13.1")  # arrow target used
            self.assertEqual(rows[("androidx.core", "core-ktx")]["manual_review"], "N")
            self.assertEqual(rows[("com.itextpdf", "itext7-core")]["manual_review"], "Y")        # AGPL wording
            self.assertEqual(rows[("com.itextpdf", "kernel")]["license_source"], "parent-pom")
            self.assertEqual(rows[("com.example", "no-license")]["pom_licenses"], "NONE DECLARED")
            self.assertEqual(rows[("com.example", "no-license")]["manual_review"], "Y")

    def test_empty_input_fails(self):
        with tempfile.TemporaryDirectory() as d:
            deps = os.path.join(d, "deps.txt"); open(deps, "w").write("nothing here\n")
            r = subprocess.run([sys.executable, SCRIPT, "--deps", deps, "--cache", d, "--out", os.path.join(d, "o.csv")], capture_output=True, text=True)
            self.assertEqual(r.returncode, 2)

if __name__ == "__main__":
    unittest.main()
