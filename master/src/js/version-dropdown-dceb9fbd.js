(function() {
    async function addVersionDropdown(menuBar) {
        if (document.querySelector('#mdbook-version-select')) return;

        // Fetch versions.json
        let versions = [];
        try {
            const res = await fetch('/meta-tegra/versions.json');
            if (res.ok) {
                versions = await res.json();
            } else {
                console.warn('Could not fetch versions.json:', res.status);
            }
        } catch (err) {
            console.error('Error fetching versions.json:', err);
            return;
        }

        if (!versions.length) return;

        // Create container
        const container = document.createElement('div');
        container.id = 'mdbook-version-container';
        container.style.cssText = `
            display: inline-block;
            margin-left: 1rem;
            vertical-align: middle;
        `;

        // Create select
        const select = document.createElement('select');
        select.id = 'mdbook-version-select';
        select.style.cssText = `
            padding: 0.3rem 0.5rem;
            font-size: 0.9rem;
            border-radius: 5px;
            border: 1px solid #ccc;
            background-color: #0b3954;
            color: white;
            font-weight: bold;
            cursor: pointer;
        `;

        // Populate options
        versions.forEach(v => {
            const opt = document.createElement('option');
            opt.value = v.folder.endsWith('/') ? v.folder : v.folder + '/';
            opt.text = v.name;
            select.appendChild(opt);
        });

        // Select current version based on URL
        const path = window.location.pathname;
        for (let i = 0; i < select.options.length; i++) {
            if (path.includes(select.options[i].value)) {
                select.selectedIndex = i;
                break;
            }
        }

        // Navigate on change
        select.addEventListener('change', e => {
            window.location.href = e.target.value;
        });

        container.appendChild(select);
        menuBar.appendChild(container);
    }

    // Wait until the menu bar exists
    const observer = new MutationObserver((mutations, obs) => {
        const menuBar = document.getElementById('mdbook-menu-bar');
        if (menuBar) {
            addVersionDropdown(menuBar);
            obs.disconnect();
        }
    });

    observer.observe(document.body, { childList: true, subtree: true });
})();

