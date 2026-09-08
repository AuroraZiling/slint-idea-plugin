//! Read-only Rust syntax analysis. Never runs or rewrites application code.
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};
use syn::{
    parse::Parser, punctuated::Punctuated, spanned::Spanned, visit::Visit, Expr, Item, Stmt, Token,
    UseTree,
};

#[derive(Deserialize)]
struct Request {
    metadata: serde_json::Value,
    #[serde(default)]
    out_dirs: BTreeMap<String, String>,
}
#[derive(Serialize, Debug)]
struct Library {
    name: String,
    path: Option<String>,
    kind: String,
    package_id: String,
    source: String,
    status: String,
    evidence: String,
}
#[derive(Serialize)]
struct Context {
    package_id: String,
    manifest: String,
    libraries: Vec<Library>,
}
#[derive(Serialize)]
struct Response {
    contexts: Vec<Context>,
    watch_files: BTreeSet<String>,
}
#[derive(Clone)]
struct Scope {
    package: String,
    file: PathBuf,
    items: Vec<Item>,
    locals: BTreeMap<String, Expr>,
    aliases: BTreeMap<String, String>,
    forbidden: BTreeSet<String>,
}
struct Analyzer {
    req: Request,
    watches: BTreeSet<String>,
    build_package: String,
}
type Result<T> = std::result::Result<T, String>;

fn path_name(p: &syn::Path) -> String {
    p.segments
        .iter()
        .map(|s| s.ident.to_string())
        .collect::<Vec<_>>()
        .join("::")
}
fn aliases(tree: &UseTree, prefix: &str, out: &mut BTreeMap<String, String>) {
    match tree {
        UseTree::Path(p) => aliases(&p.tree, &format!("{prefix}{}::", p.ident), out),
        UseTree::Name(n) => {
            out.insert(n.ident.to_string(), format!("{prefix}{}", n.ident));
        }
        UseTree::Rename(n) => {
            out.insert(n.rename.to_string(), format!("{prefix}{}", n.ident));
        }
        UseTree::Group(g) => {
            for t in &g.items {
                aliases(t, prefix, out);
            }
        }
        UseTree::Glob(_) => {}
    }
}
fn canonical(s: &Scope, p: &syn::Path) -> String {
    let n = path_name(p);
    let (head, rest) = n.split_once("::").unwrap_or((&n, ""));
    match s.aliases.get(head) {
        Some(a) if rest.is_empty() => a.clone(),
        Some(a) => format!("{a}::{rest}"),
        None => n,
    }
}
fn peel(e: &Expr) -> &Expr {
    match e {
        Expr::Paren(p) => peel(&p.expr),
        Expr::Group(g) => peel(&g.expr),
        _ => e,
    }
}
fn arguments(m: &syn::Macro) -> Result<Vec<Expr>> {
    Punctuated::<Expr, Token![,]>::parse_terminated
        .parse2(m.tokens.clone())
        .map(|p| p.into_iter().collect())
        .map_err(|e| e.to_string())
}
fn local_name(p: &syn::Pat) -> Option<String> {
    match p {
        syn::Pat::Ident(i) => Some(i.ident.to_string()),
        syn::Pat::Type(t) => local_name(&t.pat),
        _ => None,
    }
}
struct Refs(BTreeSet<String>);
impl<'a> Visit<'a> for Refs {
    fn visit_expr_path(&mut self, p: &'a syn::ExprPath) {
        if let Some(i) = p.path.get_ident() {
            self.0.insert(i.to_string());
        }
    }
}
fn refs(e: &Expr) -> BTreeSet<String> {
    let mut r = Refs(BTreeSet::new());
    r.visit_expr(e);
    r.0
}
struct Hazards {
    names: BTreeSet<String>,
    early_return: bool,
}
impl<'a> Visit<'a> for Hazards {
    fn visit_expr_binary(&mut self, e: &'a syn::ExprBinary) {
        if matches!(
            e.op,
            syn::BinOp::AddAssign(_)
                | syn::BinOp::SubAssign(_)
                | syn::BinOp::MulAssign(_)
                | syn::BinOp::DivAssign(_)
                | syn::BinOp::RemAssign(_)
                | syn::BinOp::BitXorAssign(_)
                | syn::BinOp::BitAndAssign(_)
                | syn::BinOp::BitOrAssign(_)
                | syn::BinOp::ShlAssign(_)
                | syn::BinOp::ShrAssign(_)
        ) {
            self.names.extend(refs(&e.left));
        }
        syn::visit::visit_expr_binary(self, e);
    }
    fn visit_expr_assign(&mut self, e: &'a syn::ExprAssign) {
        self.names.extend(refs(&e.left));
        syn::visit::visit_expr_assign(self, e);
    }
    fn visit_expr_reference(&mut self, e: &'a syn::ExprReference) {
        if e.mutability.is_some() {
            self.names.extend(refs(&e.expr));
        }
        syn::visit::visit_expr_reference(self, e);
    }
    fn visit_expr_method_call(&mut self, e: &'a syn::ExprMethodCall) {
        if ![
            "join",
            "clone",
            "to_string",
            "into",
            "unwrap",
            "expect",
            "as_path",
            "display",
            "with_library_paths",
            "embed_resources",
            "with_style",
            "with_include_paths",
        ]
        .contains(&e.method.to_string().as_str())
        {
            self.names.extend(refs(&e.receiver));
        }
        syn::visit::visit_expr_method_call(self, e);
    }
    fn visit_expr_return(&mut self, _: &'a syn::ExprReturn) {
        self.early_return = true;
    }
    fn visit_macro(&mut self, m: &'a syn::Macro) {
        if [
            "println",
            "eprintln",
            "print",
            "eprint",
            "assert",
            "assert_eq",
            "assert_ne",
            "format",
            "vec",
            "panic",
            "env",
            "concat",
        ]
        .iter()
        .any(|n| m.path.is_ident(n))
        {
            if let Ok(args) = arguments(m) {
                for arg in &args {
                    self.visit_expr(arg);
                }
                return;
            }
        }
        {
            // Unknown macros may mutate any identifier in their token tree.
            fn names(tokens: proc_macro2::TokenStream, out: &mut BTreeSet<String>) {
                for t in tokens {
                    match t {
                        proc_macro2::TokenTree::Ident(i) => {
                            out.insert(i.to_string());
                        }
                        proc_macro2::TokenTree::Group(g) => names(g.stream(), out),
                        _ => {}
                    }
                }
            }
            names(m.tokens.clone(), &mut self.names);
        }
    }
    fn visit_expr_closure(&mut self, e: &'a syn::ExprClosure) {
        let previous_return = self.early_return;
        syn::visit::visit_expr_closure(self, e);
        self.early_return = previous_return;
    }
    fn visit_item(&mut self, _: &'a Item) {}
}
impl Analyzer {
    fn package(&self, id: &str) -> Result<&serde_json::Value> {
        self.req.metadata["packages"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["id"] == id)
            .ok_or_else(|| format!("Package not in metadata: {id}"))
    }
    fn manifest_dir(&self, id: &str) -> Result<PathBuf> {
        Ok(Path::new(
            self.package(id)?["manifest_path"]
                .as_str()
                .ok_or("Missing manifest")?,
        )
        .parent()
        .unwrap()
        .to_path_buf())
    }
    fn scope(&mut self, package: &str, file: &Path) -> Result<Scope> {
        self.watches.insert(file.to_string_lossy().into());
        let ast = syn::parse_file(
            &fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?,
        )
        .map_err(|e| format!("{}: {e}", file.display()))?;
        let mut s = Scope {
            package: package.into(),
            file: file.into(),
            items: ast.items,
            locals: BTreeMap::new(),
            aliases: BTreeMap::new(),
            forbidden: BTreeSet::new(),
        };
        for i in &s.items {
            if let Item::Use(u) = i {
                aliases(&u.tree, "", &mut s.aliases);
            }
        }
        Ok(s)
    }
    fn function_scope(&self, s: &Scope, f: &syn::ItemFn) -> Result<(Scope, Expr)> {
        if !f.sig.inputs.is_empty() {
            return Err("Functions with arguments are unsupported".into());
        }
        if f.attrs.iter().any(|a| {
            !["doc", "inline", "allow"]
                .iter()
                .any(|n| a.path().is_ident(n))
        }) {
            return Err("Attributed/cfg functions require conditional context".into());
        }
        let mut next = s.clone();
        next.locals.clear();
        next.forbidden.clear();
        let mut hazards = Hazards {
            names: BTreeSet::new(),
            early_return: false,
        };
        let last = f.block.stmts.last().ok_or("Empty function")?;
        for stmt in &f.block.stmts {
            if let Stmt::Local(l) = stmt {
                if let (Some(n), Some(init)) = (local_name(&l.pat), &l.init) {
                    if next
                        .locals
                        .insert(n.clone(), (*init.expr).clone())
                        .is_some()
                    {
                        next.forbidden.insert(n);
                    }
                }
            }
            if let Stmt::Item(Item::Use(u)) = stmt {
                aliases(&u.tree, "", &mut next.aliases);
            }
            if !std::ptr::eq(stmt, last) {
                hazards.visit_stmt(stmt);
            }
        }
        next.forbidden.extend(hazards.names);
        if hazards.early_return {
            return Err("Conditional or early return is unsupported".into());
        }
        let tail = match last {
            Stmt::Expr(e, None) => e.clone(),
            Stmt::Expr(Expr::Return(r), _) => *r.expr.clone().ok_or("Return without path")?,
            _ => return Err("No return expression".into()),
        };
        Ok((next, tail))
    }
    fn local<'a>(&self, s: &'a Scope, name: &str) -> Result<&'a Expr> {
        if s.forbidden.contains(name) {
            return Err(format!("Potential mutation or shadowing of `{name}`"));
        }
        s.locals
            .get(name)
            .ok_or_else(|| format!("Unknown binding `{name}`"))
    }
    fn function(&mut self, s: &Scope, name: &str, depth: usize) -> Result<String> {
        let mut parts: Vec<_> = name.split("::").collect();
        let mut next = s.clone();
        if parts.first() == Some(&"crate") {
            return Err("crate-qualified function paths are unsupported".into());
        }
        if parts.first() == Some(&"self") {
            parts.remove(0);
        }
        if parts.len() > 1 {
            let head = parts.remove(0);
            let module = next.items.iter().find_map(|i| {
                if let Item::Mod(m) = i {
                    (m.ident == head).then_some(m.clone())
                } else {
                    None
                }
            });
            if let Some(m) = module {
                if m.attrs.iter().any(|a| !a.path().is_ident("path")) {
                    return Err("Conditional modules are unsupported".into());
                }
                if m.content.is_some() {
                    return Err("Inline module traversal is unsupported".into());
                } else {
                    let path = m.attrs.iter().find_map(|a| match &a.meta {
                        syn::Meta::NameValue(n) if n.path.is_ident("path") => match &n.value {
                            Expr::Lit(l) => match &l.lit {
                                syn::Lit::Str(v) => Some(v.value()),
                                _ => None,
                            },
                            _ => None,
                        },
                        _ => None,
                    });
                    let parent = next.file.parent().unwrap();
                    let file = parent.join(
                        path.ok_or("Out-of-line modules require an explicit #[path] attribute")?,
                    );
                    next = self.scope(&s.package, &file)?;
                }
            } else {
                let nodes = self.req.metadata["resolve"]["nodes"]
                    .as_array()
                    .ok_or("Missing dependency resolution graph")?;
                let node = nodes
                    .iter()
                    .find(|n| n["id"] == s.package)
                    .ok_or("Missing dependency node")?;
                let deps: Vec<_> = node["deps"]
                    .as_array()
                    .ok_or("Missing dependencies")?
                    .iter()
                    .filter(|d| d["name"] == head)
                    .collect();
                if deps.len() != 1 {
                    return Err(format!("Ambiguous or missing dependency `{head}`"));
                }
                let id = deps[0]["pkg"].as_str().unwrap().to_owned();
                let pkg = self.package(&id)?;
                let targets: Vec<_> = pkg["targets"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .filter(|t| {
                        t["kind"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .any(|k| k == "lib" || k == "rlib")
                    })
                    .collect();
                if targets.len() != 1 {
                    return Err("Ambiguous dependency library target".into());
                }
                let file = PathBuf::from(targets[0]["src_path"].as_str().unwrap());
                next = self.scope(&id, &file)?;
            }
            return self.function(&next, &parts.join("::"), depth + 1);
        }
        let functions: Vec<_> = next
            .items
            .iter()
            .filter_map(|i| {
                if let Item::Fn(f) = i {
                    (f.sig.ident == parts[0]).then_some(f.clone())
                } else {
                    None
                }
            })
            .collect();
        if functions.len() != 1 {
            return Err(format!("Ambiguous or missing function `{name}`"));
        }
        let (scope, tail) = self.function_scope(&next, &functions[0])?;
        self.value(&scope, &tail, depth + 1)
            .map_err(|e| format!("{}:{}: {e}", scope.file.display(), tail.span().start().line))
    }
    fn value(&mut self, s: &Scope, expr: &Expr, depth: usize) -> Result<String> {
        if depth > 48 {
            return Err("Recursive or overly deep path expression".into());
        }
        match peel(expr) {
            Expr::Lit(l) => match &l.lit {
                syn::Lit::Str(v) => Ok(v.value()),
                _ => Err("Non-string path".into()),
            },
            Expr::Path(p) => {
                let n = path_name(&p.path);
                self.value(s, self.local(s, &n)?, depth + 1)
            }
            Expr::MethodCall(m) => {
                let v = self.value(s, &m.receiver, depth + 1)?;
                match m.method.to_string().as_str() {
                    "into" | "to_string" | "unwrap" | "clone" if m.args.is_empty() => Ok(v),
                    "expect" if m.args.len() == 1 => Ok(v),
                    "join" if m.args.len() == 1 => Ok(PathBuf::from(v)
                        .join(self.value(s, &m.args[0], depth + 1)?)
                        .to_string_lossy()
                        .into()),
                    _ => Err(format!("Unsupported path method `{}`", m.method)),
                }
            }
            Expr::Call(c) => {
                let Expr::Path(p) = peel(&c.func) else {
                    return Err("Indirect function call".into());
                };
                let n = canonical(s, &p.path);
                match n.as_str() {
                    "std::path::PathBuf::from" if c.args.len() == 1 => {
                        self.value(s, &c.args[0], depth + 1)
                    }
                    "std::env::var_os" | "std::env::var" if c.args.len() == 1 => {
                        match self.value(s, &c.args[0], depth + 1)?.as_str() {
                            "CARGO_MANIFEST_DIR" => Ok(self.manifest_dir(&self.build_package)?.to_string_lossy().into()),
                            "OUT_DIR" => self.req.out_dirs.get(&self.build_package).cloned().ok_or_else(|| "PendingBuild: no build-script-executed OUT_DIR for this package".into()),
                            v => Err(format!("Unsupported environment variable `{v}`")),
                        }
                    }
                    _ if c.args.is_empty() => self.function(s, &n, depth + 1),
                    _ => Err(format!("Unsupported function `{n}`")),
                }
            }
            Expr::Macro(m) => {
                let args = arguments(&m.mac)?;
                match canonical(s, &m.mac.path).as_str() {
                    "env"
                        if args.len() == 1
                            && self.value(s, &args[0], depth + 1)? == "CARGO_MANIFEST_DIR" =>
                    {
                        Ok(self.manifest_dir(&s.package)?.to_string_lossy().into())
                    }
                    "concat" => {
                        let mut v = String::new();
                        for a in args {
                            v.push_str(&self.value(s, &a, depth + 1)?);
                        }
                        Ok(v)
                    }
                    _ => Err("Unsupported path macro (env! uses the defining crate)".into()),
                }
            }
            _ => Err("Unsupported path expression or control flow".into()),
        }
    }
    fn config(&mut self, s: &Scope, expr: &Expr, depth: usize) -> Result<Vec<(String, Expr)>> {
        if depth > 48 {
            return Err("Recursive configuration".into());
        }
        match peel(expr) {
            Expr::Path(p) => self.config(s, self.local(s, &path_name(&p.path))?, depth + 1),
            Expr::MethodCall(m) if m.method == "with_library_paths" && m.args.len() == 1 => {
                self.builder(s, &m.receiver, depth + 1)?;
                self.map(s, &m.args[0], depth + 1)
            }
            Expr::MethodCall(m)
                if ["embed_resources", "with_style", "with_include_paths"]
                    .contains(&m.method.to_string().as_str()) =>
            {
                self.config(s, &m.receiver, depth + 1)
            }
            _ => Err("Unsupported Slint CompilerConfiguration data flow".into()),
        }
    }
    fn builder(&self, s: &Scope, expr: &Expr, depth: usize) -> Result<()> {
        if depth > 48 {
            return Err("Recursive configuration".into());
        }
        match peel(expr) {
            Expr::Path(p) => self.builder(s, self.local(s, &path_name(&p.path))?, depth + 1),
            Expr::Call(c) if c.args.is_empty() => match peel(&c.func) {
                Expr::Path(p)
                    if canonical(s, &p.path) == "slint_build::CompilerConfiguration::new" =>
                {
                    Ok(())
                }
                _ => Err("Unknown configuration constructor".into()),
            },
            Expr::MethodCall(m)
                if [
                    "with_library_paths",
                    "embed_resources",
                    "with_style",
                    "with_include_paths",
                ]
                .contains(&m.method.to_string().as_str()) =>
            {
                self.builder(s, &m.receiver, depth + 1)
            }
            _ => Err("Unknown configuration receiver".into()),
        }
    }
    fn map(&mut self, s: &Scope, expr: &Expr, depth: usize) -> Result<Vec<(String, Expr)>> {
        if depth > 48 {
            return Err("Recursive library map".into());
        }
        if let Expr::Path(p) = peel(expr) {
            return self.map(s, self.local(s, &path_name(&p.path))?, depth + 1);
        }
        if let Expr::Call(c) = peel(expr) {
            if let Expr::Path(p) = peel(&c.func) {
                if canonical(s, &p.path) == "std::collections::HashMap::from" && c.args.len() == 1 {
                    if let Expr::Array(a) = peel(&c.args[0]) {
                        return a
                            .elems
                            .iter()
                            .map(|e| match peel(e) {
                                Expr::Tuple(t) if t.elems.len() == 2 => {
                                    Ok((self.value(s, &t.elems[0], depth + 1)?, t.elems[1].clone()))
                                }
                                _ => Err("Unsupported map entry".into()),
                            })
                            .collect();
                    }
                }
            }
        }
        Err("Expected HashMap::from([...]) library map".into())
    }
    fn analyze(mut self) -> Response {
        let mut contexts = Vec::new();
        let ids = self.req.metadata["workspace_members"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        for id in ids {
            let id = id.as_str().unwrap().to_owned();
            self.build_package = id.clone();
            let pkg = self.package(&id).unwrap().clone();
            let manifest = pkg["manifest_path"].as_str().unwrap().to_owned();
            self.watches.insert(manifest.clone());
            let mut ctx = Context {
                package_id: id.clone(),
                manifest,
                libraries: Vec::new(),
            };
            for target in pkg["targets"].as_array().unwrap() {
                if !target["kind"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|k| k == "custom-build")
                {
                    continue;
                }
                let file = PathBuf::from(target["src_path"].as_str().unwrap());
                let result = (|| -> Result<()> {
                    let base = self.scope(&id, &file)?;
                    let main = base
                        .items
                        .iter()
                        .find_map(|i| {
                            if let Item::Fn(f) = i {
                                (f.sig.ident == "main").then_some(f.clone())
                            } else {
                                None
                            }
                        })
                        .ok_or("No build script main")?;
                    // main commonly ends in a semicolon, unlike path-returning functions.
                    let mut padded = main.clone();
                    padded
                        .block
                        .stmts
                        .push(Stmt::Expr(syn::parse_quote!(()), None));
                    let (scope, _) = self.function_scope(&base, &padded)?;
                    struct Calls {
                        values: Vec<(syn::ExprCall, bool)>,
                        conditional: bool,
                    }
                    impl<'a> Visit<'a> for Calls {
                        fn visit_expr_call(&mut self, c: &'a syn::ExprCall) {
                            self.values.push((c.clone(), self.conditional));
                            syn::visit::visit_expr_call(self, c);
                        }
                        fn visit_expr(&mut self, e: &'a Expr) {
                            let previous = self.conditional;
                            if matches!(
                                e,
                                Expr::If(_)
                                    | Expr::Match(_)
                                    | Expr::ForLoop(_)
                                    | Expr::While(_)
                                    | Expr::Loop(_)
                                    | Expr::Closure(_)
                            ) {
                                self.conditional = true;
                            }
                            syn::visit::visit_expr(self, e);
                            self.conditional = previous;
                        }
                        fn visit_item(&mut self, _: &'a Item) {}
                    }
                    let mut calls = Calls {
                        values: Vec::new(),
                        conditional: false,
                    };
                    calls.visit_block(&main.block);
                    for (call, conditional) in calls.values {
                        let Expr::Path(p) = peel(&call.func) else {
                            continue;
                        };
                        if canonical(&scope, &p.path) != "slint_build::compile_with_config"
                            || call.args.len() != 2
                        {
                            continue;
                        }
                        if conditional {
                            return Err(format!(
                                "{}:{}: conditional Slint compilation is unsupported",
                                file.display(),
                                call.span().start().line
                            ));
                        }
                        let entries = self.config(&scope, &call.args[1], 0)?;
                        for (name, e) in entries {
                            let source = format!("{}:{}", file.display(), e.span().start().line);
                            let result = self.value(&scope, &e, 0);
                            let (path, kind, status, evidence) = match result {
                                Ok(p) => {
                                    let p = self.manifest_dir(&id)?.join(p);
                                    let status = if p.exists() {
                                        "Resolved"
                                    } else {
                                        "PendingBuild"
                                    };
                                    let kind = if p.is_dir() { "Directory" } else { "File" };
                                    (Some(p.to_string_lossy().into_owned()), kind.into(), status.into(), format!("compile_with_config → with_library_paths; Cargo package {id}; AST path dependencies"))
                                }
                                Err(e) => (
                                    None,
                                    "Unknown".into(),
                                    if e.contains("PendingBuild:") {
                                        "PendingBuild"
                                    } else {
                                        "Unresolved"
                                    }
                                    .into(),
                                    e,
                                ),
                            };
                            ctx.libraries.push(Library {
                                name,
                                path,
                                kind,
                                package_id: id.clone(),
                                source,
                                status,
                                evidence,
                            });
                        }
                    }
                    Ok(())
                })();
                if let Err(e) = result {
                    ctx.libraries.clear();
                    ctx.libraries.push(Library {
                        name: "<configuration>".into(),
                        path: None,
                        kind: "Unknown".into(),
                        package_id: id.clone(),
                        source: file.display().to_string(),
                        status: "Unresolved".into(),
                        evidence: e,
                    });
                }
            }
            let mut names = BTreeMap::<String, BTreeSet<Option<String>>>::new();
            for l in &ctx.libraries {
                names
                    .entry(l.name.clone())
                    .or_default()
                    .insert(l.path.clone());
            }
            for l in &mut ctx.libraries {
                if names[&l.name].len() > 1 {
                    l.status = "Ambiguous".into();
                    l.evidence =
                        "Multiple Slint configurations define different paths for this name".into();
                }
            }
            contexts.push(ctx);
        }
        Response {
            contexts,
            watch_files: self.watches,
        }
    }
}
fn main() {
    let result = (|| -> Result<Response> {
        let file = std::env::args_os()
            .nth(1)
            .ok_or("Expected request JSON file")?;
        let req = serde_json::from_str(&fs::read_to_string(file).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        Ok(Analyzer {
            req,
            watches: BTreeSet::new(),
            build_package: String::new(),
        }
        .analyze())
    })();
    match result {
        Ok(r) => println!("{}", serde_json::to_string(&r).unwrap()),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn analyzer() -> Analyzer {
        Analyzer {
            req: Request {
                metadata: serde_json::json!({"packages":[{"id":"app", "manifest_path":"C:/workspace/app/Cargo.toml"},{"id":"dependency", "manifest_path":"C:/registry/versioned-dependency/Cargo.toml"}]}),
                out_dirs: BTreeMap::from([("app".into(), "C:/target/debug/build/app/out".into())]),
            },
            watches: BTreeSet::new(),
            build_package: "app".into(),
        }
    }
    fn scope(body: &str) -> (Analyzer, Scope, Expr) {
        let a = analyzer();
        let f: syn::ItemFn =
            syn::parse_str(&format!("fn generate() -> PathBuf {{ {body} }}")).unwrap();
        let s = Scope {
            package: "app".into(),
            file: "C:/workspace/app/build.rs".into(),
            items: vec![],
            locals: BTreeMap::new(),
            aliases: BTreeMap::from([("PathBuf".into(), "std::path::PathBuf".into())]),
            forbidden: BTreeSet::new(),
        };
        let (s, e) = a.function_scope(&s, &f).unwrap();
        (a, s, e)
    }
    #[test]
    fn generated_path_ignores_unrelated_json_and_writes() {
        let (mut a, s, e) = scope(
            r#"let json = serde_json::from_str(&std::fs::read_to_string("en.json").unwrap()).unwrap(); let mut slint = String::new(); for key in json { slint.push_str(key); } let out = PathBuf::from(std::env::var_os("OUT_DIR").unwrap()); std::fs::write(out.join("catalog.slint"), slint).unwrap(); out.join("catalog.slint")"#,
        );
        assert_eq!(
            PathBuf::from(a.value(&s, &e, 0).unwrap()),
            PathBuf::from("C:/target/debug/build/app/out").join("catalog.slint")
        );
    }
    #[test]
    fn mutation_is_not_guessed() {
        for body in [
            r#"let mut out = PathBuf::from("a"); out.push("b"); out.join("c")"#,
            r#"let mut out = PathBuf::from("a"); if condition { out = PathBuf::from("b"); } out"#,
            r#"let mut out = PathBuf::from("a"); unknown(&mut out); out"#,
            r#"let out = PathBuf::from("a"); let out = out.join("b"); out"#,
            r#"let mut out = PathBuf::from("a"); modify!(out); out"#,
        ] {
            let (mut a, s, e) = scope(body);
            assert!(a.value(&s, &e, 0).is_err(), "{body}");
        }
    }
    #[test]
    fn unsupported_control_flow_and_calls() {
        for body in [
            r#"if cond { PathBuf::from("a") } else { PathBuf::from("b") }"#,
            "unknown()",
        ] {
            let (mut a, s, e) = scope(body);
            assert!(a.value(&s, &e, 0).is_err());
        }
    }
    #[test]
    fn missing_output_is_pending() {
        let (mut a, s, e) =
            scope(r#"PathBuf::from(std::env::var_os("OUT_DIR").unwrap()).join("x.slint")"#);
        a.req.out_dirs.clear();
        assert!(a.value(&s, &e, 0).unwrap_err().starts_with("PendingBuild:"));
    }
    #[test]
    fn macro_environment_is_defining_crate() {
        let (mut a, s, e) =
            scope(r#"concat!(env!("CARGO_MANIFEST_DIR"), "/entry.slint").to_string()"#);
        assert_eq!(a.value(&s, &e, 0).unwrap(), "C:/workspace/app/entry.slint");
    }
    #[test]
    fn dependency_runtime_environment_remains_callers_build_environment() {
        let (mut a, mut s, e) =
            scope(r#"PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap())"#);
        s.package = "dependency".into();
        assert_eq!(a.value(&s, &e, 0).unwrap(), "C:/workspace/app");
        let macro_expr: Expr = syn::parse_str(r#"env!("CARGO_MANIFEST_DIR")"#).unwrap();
        assert_eq!(
            a.value(&s, &macro_expr, 0).unwrap(),
            "C:/registry/versioned-dependency"
        );
    }
    #[test]
    fn config_aliases_and_names() {
        let (mut a, mut s, _) = scope("()");
        aliases(
            &syn::parse_str::<syn::ItemUse>("use std::collections::HashMap as Libraries;")
                .unwrap()
                .tree,
            "",
            &mut s.aliases,
        );
        let e: Expr=syn::parse_str(r#"slint_build::CompilerConfiguration::new().with_library_paths(Libraries::from([("translations".into(), "i18n.slint".into())])).embed_resources(kind)"#).unwrap();
        let map = a.config(&s, &e, 0).unwrap();
        assert_eq!(map[0].0, "translations");
        assert_eq!(a.value(&s, &map[0].1, 0).unwrap(), "i18n.slint");
    }
    #[test]
    fn real_fixture_ast_includes_cross_file_generator_and_renamed_dependency() {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../src/test/fixtures/cargo-libraries");
        let metadata = serde_json::json!({
            "workspace_members":["app", "other"],
            "packages":[
                {"id":"app","manifest_path":root.join("app/Cargo.toml"),"targets":[{"kind":["custom-build"],"src_path":root.join("app/build.rs")}]},
                {"id":"other","manifest_path":root.join("other/Cargo.toml"),"targets":[{"kind":["custom-build"],"src_path":root.join("other/build.rs")}]},
                {"id":"shapes-version-0.7","manifest_path":root.join("shapes/Cargo.toml"),"targets":[{"kind":["lib"],"src_path":root.join("shapes/src/lib.rs")}]}
            ],
            "resolve":{"nodes":[{"id":"app","deps":[{"name":"renamed_shapes","pkg":"shapes-version-0.7"}]},{"id":"other","deps":[]}]}
        });
        let r = Analyzer {
            req: Request {
                metadata,
                out_dirs: BTreeMap::new(),
            },
            watches: BTreeSet::new(),
            build_package: String::new(),
        }
        .analyze();
        let app = &r.contexts[0];
        assert_eq!(
            app.libraries
                .iter()
                .find(|l| l.name == "i18n")
                .unwrap()
                .status,
            "PendingBuild"
        );
        assert_eq!(
            app.libraries
                .iter()
                .find(|l| l.name == "shapes")
                .unwrap()
                .status,
            "Resolved"
        );
        assert!(r.watch_files.iter().any(|s| s.ends_with("i18n.rs")));
        assert_eq!(
            r.contexts[1]
                .libraries
                .iter()
                .find(|l| l.name == "folder")
                .unwrap()
                .kind,
            "Directory"
        );
        assert_eq!(
            r.contexts[1]
                .libraries
                .iter()
                .find(|l| l.name == "translations")
                .unwrap()
                .status,
            "Resolved"
        );
    }
}
