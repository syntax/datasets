import os
import subprocess
import shutil
from pathlib import Path

GH_JAVA_PROJECTS = {
    # 'apache-commons-cli': 'https://github.com/apache/commons-cli.git',
    # 'google-guava': 'https://github.com/google/guava.git',
    # 'ok-http': 'https://github.com/square/okhttp.git',
    # 'h2': 'https://github.com/h2database/h2database.git',
    # 'zaproxy': 'https://github.com/zaproxy/zaproxy.git',
    # 'jabref': 'https://github.com/JabRef/jabref.git',
    # 'elastic-search': 'https://github.com/elastic/elasticsearch.git',
    # 'killbill': 'https://github.com/killbill/killbill.git',
    # 'drool': 'https://github.com/kiegroup/drools.git',
    'signal-server': 'https://github.com/signalapp/Signal-Server.git',
}

BASE_DIR = Path(__file__).parent
PROJECTS_DIR = BASE_DIR / 'projects'
BEFORE_DIR = BASE_DIR / 'before'
AFTER_DIR = BASE_DIR / 'after'

def clone_repos():
    """Clone repositories into the projects directory."""
    PROJECTS_DIR.mkdir(exist_ok=True)
    for project_name, repo_url in GH_JAVA_PROJECTS.items():
        project_path = PROJECTS_DIR / project_name
        if not project_path.exists():
            print(f"Cloning {repo_url} into {project_path}")
            subprocess.run(['git', 'clone', repo_url, str(project_path)], check=True)

def checkout_commit(repo_path, commit_hash):
    """Checkout a specific commit."""
    try:
        subprocess.run(['git', 'stash'], cwd=repo_path, check=True)
        subprocess.run(['git', 'checkout', commit_hash], cwd=repo_path, check=True)
    except subprocess.CalledProcessError as e:
        print(f"Error during checkout of commit {commit_hash}: {e}")

def get_previous_commit(repo_path, commit_hash):
    """Get the commit hash before the given commit."""
    result = subprocess.run(
        ['git', 'rev-parse', f'{commit_hash}^'],
        cwd=repo_path,
        capture_output=True,
        text=True,
        check=True
    )
    return result.stdout.strip()

def process_commits():
    """Process commits dynamically based on directory structure."""
    for project_name in GH_JAVA_PROJECTS.keys():
        repo_path = PROJECTS_DIR / project_name
        if not repo_path.exists():
            print(f"Repository for {project_name} does not exist. Skipping.")
            continue

        for stage, stage_dir in [('before', BEFORE_DIR), ('after', AFTER_DIR)]:
            project_stage_dir = stage_dir / project_name
            if not project_stage_dir.exists():
                print(f"Stage directory {project_stage_dir} does not exist. Skipping.")
                continue

            for commit_dir in project_stage_dir.iterdir():
                if commit_dir.is_dir():
                    commit_hash = commit_dir.name
                    target_commit = None

                    if stage == 'before':
                        try:
                            target_commit = get_previous_commit(repo_path, commit_hash)
                        except subprocess.CalledProcessError:
                            print(f"Cannot determine previous commit for {commit_hash}. Likely the first commit.")
                            continue
                    elif stage == 'after':
                        target_commit = commit_hash

                    if target_commit:
                        print(f"Processing {stage} commit {target_commit} for {project_name}")
                        checkout_commit(repo_path, target_commit)
                        compile_project(repo_path, commit_dir)


def copy_compiled_files(compiled_dir, commit_dir):
    """Copy compiled .class files matching .java dataset files into the commit directory."""
    if not compiled_dir.exists():
        print(f"Compiled directory {compiled_dir} does not exist. Skipping.")
        return

    # Match dataset .java files with corresponding .class files
    dataset_java_files = list(commit_dir.rglob("*.java"))
    if not dataset_java_files:
        print(f"No Java files found in dataset for commit {commit_dir}. Skipping.")
        return

    target_output_dir = commit_dir / 'compiled'
    target_output_dir.mkdir(parents=True, exist_ok=True)

    for java_file in dataset_java_files:
        class_name = java_file.stem
        matching_class_files = list(compiled_dir.rglob(f"{class_name}*.class"))  # match all related .class files
        if matching_class_files:
            for class_file in matching_class_files:
                relative_path = class_file.relative_to(compiled_dir)
                target_file = target_output_dir / relative_path
                target_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(class_file, target_file)
                print(f"Copied {class_file} to {target_file}")
        else:
            print(f"Compiled file for {java_file.name} not found in {compiled_dir}. Skipping.")



def compile_project(repo_path, commit_dir):
    """Compile the project using Gradle, Maven, or fallback to manual compilation."""
    gradlew_path = repo_path / 'gradlew'
    build_gradle = repo_path / 'build.gradle'
    pom_xml = repo_path / 'pom.xml'

    try:
        if gradlew_path.exists():
            print(f">> Using Gradle Wrapper to build {repo_path}")
            subprocess.run([str(gradlew_path), 'build'], cwd=repo_path, check=True)
            copy_compiled_files(repo_path / 'build' / 'classes', commit_dir)
        elif build_gradle.exists():
            print(f">> Using system Gradle to build {repo_path}")
            subprocess.run(['gradle', 'build'], cwd=repo_path, check=True)
            copy_compiled_files(repo_path / 'build' / 'classes', commit_dir)
        elif pom_xml.exists():
            print(f">> Using Maven to build {repo_path}")
            subprocess.run(['mvn', 'clean', 'compile'], cwd=repo_path, check=True)
            copy_compiled_files(repo_path / 'target' / 'classes', commit_dir)
        else:
            print(f">> No build system detected in {repo_path}. Attempting manual compilation.")
            compile_java_manually(repo_path, commit_dir)
    except subprocess.CalledProcessError as e:
        print(f"Build failed for {repo_path}. Error: {e}")


# this is a bit of a mess of a function but whatever
def compile_java_manually(repo_path, commit_dir):
    """Compile the entire project and match relevant .class files."""
    src_dir = repo_path / 'src'
    if not src_dir.exists():
        print(f"No source directory found in {repo_path}. Skipping manual compilation.")
        return

    dataset_java_files = list(commit_dir.rglob("*.java"))
    if not dataset_java_files:
        print(f"No Java files found in dataset for commit {commit_dir}. Skipping manual compilation.")
        return

    output_dir = repo_path / 'compiled'
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Compiling the entire project into {output_dir}")
    javac_cmd = ['javac', '-d', str(output_dir), '-sourcepath', str(src_dir)] + [str(f) for f in src_dir.rglob("*.java")]
    try:
        subprocess.run(javac_cmd, check=True)
    except subprocess.CalledProcessError as e:
        print(f"Error during manual compilation: {e}")
        return

    # match relevant .class files
    target_output_dir = commit_dir / 'compiled'
    target_output_dir.mkdir(parents=True, exist_ok=True)

    for java_file in dataset_java_files:
        class_name = java_file.stem
        matching_class_files = list(output_dir.rglob(f"{class_name}*.class"))  # match all related .class files
        if matching_class_files:
            for class_file in matching_class_files:
                relative_path = class_file.relative_to(output_dir)
                target_file = target_output_dir / relative_path
                target_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(class_file, target_file)
                print(f"Copied {class_file} to {target_file}")
        else:
            print(f"Compiled file for {java_file.name} not found. Skipping.")

    shutil.rmtree(output_dir, ignore_errors=True)




if __name__ == "__main__":
    try:
        clone_repos()
        process_commits()

    except subprocess.CalledProcessError as e:
        print(f"Error during execution: {e}")